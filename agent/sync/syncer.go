package cloudsync

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"sync"
	"time"

	"github.com/Aditya1711-tech/cadence/agent/internal/event"
)

// MaxBatch is the most events sent per ingest POST (§6; the backend returns 413
// above this).
const MaxBatch = 1000

// Defaults for Config.
const (
	defaultLookback   = 48 * time.Hour     // re-scan floor: catch recently-inserted events
	defaultPruneAfter = 7 * 24 * time.Hour // keep dedupe rows this long behind the watermark
	defaultFirstRun   = 10 * time.Second   // first sync shortly after start
	defaultMaxBackoff = 30 * time.Minute   // backoff ceiling
)

// ErrReenrollRequired means the refresh token was rejected (family revoked,
// member removed, device de-authorized). Sync cannot proceed until the user
// re-enrolls; the loop keeps reloading credentials so a fresh enroll (done in a
// separate `cadence-agent enroll` process) is picked up automatically.
var ErrReenrollRequired = errors.New("cloudsync: re-enrollment required")

// EventReader is the slice of the local store the syncer needs: a windowed read
// by ts_start. The P1-A *store.Store satisfies it; tests inject a fake. The
// syncer never writes to the local store (ownership: it owns only /agent/sync/).
type EventReader interface {
	Query(from, to time.Time) ([]event.Event, error)
}

// Config tunes the sync loop. Zero values fall back to sensible defaults.
type Config struct {
	Interval   time.Duration // loop cadence (CADENCE_SYNC_INTERVAL_SEC)
	Lookback   time.Duration // re-scan floor behind now after a clean drain
	PruneAfter time.Duration // delete dedupe rows older than watermark − this
	BatchSize  int           // events per POST (≤ MaxBatch)
	FirstRun   time.Duration // delay before the first cycle
	MaxBackoff time.Duration // transient-failure backoff ceiling
	Logger     *slog.Logger
	now        func() time.Time // injectable clock (tests)
}

func (c *Config) withDefaults() {
	if c.Interval <= 0 {
		c.Interval = 5 * time.Minute
	}
	if c.Lookback <= 0 {
		c.Lookback = defaultLookback
	}
	if c.PruneAfter <= 0 {
		c.PruneAfter = defaultPruneAfter
	}
	if c.BatchSize <= 0 || c.BatchSize > MaxBatch {
		c.BatchSize = MaxBatch
	}
	if c.FirstRun <= 0 {
		c.FirstRun = defaultFirstRun
	}
	if c.MaxBackoff <= 0 {
		c.MaxBackoff = defaultMaxBackoff
	}
	if c.Logger == nil {
		c.Logger = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	if c.now == nil {
		c.now = func() time.Time { return time.Now().UTC() }
	}
}

// Syncer drains the local store to the cloud. One Syncer = one goroutine; refresh
// is serialized so a rotated refresh token is never reused.
type Syncer struct {
	reader EventReader
	state  *State
	keys   *Keystore
	client *Client
	cfg    Config

	refreshMu sync.Mutex
}

// NewSyncer wires the parts together.
func NewSyncer(reader EventReader, state *State, keys *Keystore, client *Client, cfg Config) *Syncer {
	cfg.withDefaults()
	return &Syncer{reader: reader, state: state, keys: keys, client: client, cfg: cfg}
}

// Run drives the loop until ctx is cancelled. It schedules the first cycle after
// cfg.FirstRun, then re-schedules each cycle based on the outcome: cfg.Interval
// on success, exponential backoff on transient failure.
func (s *Syncer) Run(ctx context.Context) {
	backoff := NewBackoff(s.cfg.Interval, s.cfg.MaxBackoff, 2.0, nil)
	timer := time.NewTimer(s.cfg.FirstRun)
	defer timer.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-timer.C:
			next := s.cfg.Interval
			err := s.RunOnce(ctx)
			switch {
			case err == nil || errors.Is(err, ErrNotEnrolled):
				backoff.Reset()
			case errors.Is(err, context.Canceled):
				return
			case errors.Is(err, ErrReenrollRequired):
				s.cfg.Logger.Warn("cloud sync paused: re-enrollment required (run `cadence-agent enroll <code>`)")
				backoff.Reset() // not transient; just wait one interval and re-check creds
			default:
				next = backoff.Next()
				s.cfg.Logger.Warn("cloud sync cycle failed; backing off",
					"err", err, "retry_in", next.String(), "failures", backoff.Failures())
			}
			timer.Reset(next)
		}
	}
}

// RunOnce performs one full drain: pull the window since the watermark, filter
// out already-synced events, POST in batches (refreshing on 401), mark synced,
// then advance the watermark and prune. Returns ErrNotEnrolled (no-op), a
// transient error (caller backs off), or ErrReenrollRequired.
func (s *Syncer) RunOnce(ctx context.Context) error {
	if !s.keys.Enrolled() {
		return ErrNotEnrolled
	}
	creds, err := s.keys.Load()
	if err != nil {
		return err
	}

	wm, err := s.state.Watermark()
	if err != nil {
		return err
	}
	to := s.cfg.now()
	candidates, err := s.reader.Query(wm, to.Add(time.Millisecond))
	if err != nil {
		return err
	}
	unsynced, err := s.state.Unsynced(candidates)
	if err != nil {
		return err
	}

	if len(unsynced) == 0 {
		return s.finishClean(to)
	}

	access := creds.AccessToken
	sent := 0
	for start := 0; start < len(unsynced); start += s.cfg.BatchSize {
		end := start + s.cfg.BatchSize
		if end > len(unsynced) {
			end = len(unsynced)
		}
		chunk := unsynced[start:end]
		res, err := s.ingestWithRefresh(ctx, &access, &creds, chunk)
		if err != nil {
			// Leave un-acked events un-synced; the watermark is NOT advanced on
			// failure, so the next cycle re-scans and retries exactly them.
			if sent > 0 {
				s.cfg.Logger.Info("cloud sync partial", "sent", sent, "remaining", len(unsynced)-sent)
			}
			return err
		}
		if err := s.state.MarkSynced(chunk); err != nil {
			return err
		}
		sent += len(chunk)
		s.cfg.Logger.Debug("cloud sync batch ok",
			"received", res.Received, "stored", res.Stored, "duplicates", res.Duplicates)
	}
	s.cfg.Logger.Info("cloud sync complete", "sent", sent)
	return s.finishClean(to)
}

// finishClean advances the watermark to to−Lookback (monotonic, never past an
// unsynced event since the window drained clean) and prunes stale dedupe rows.
func (s *Syncer) finishClean(to time.Time) error {
	wm, err := s.state.Watermark()
	if err != nil {
		return err
	}
	target := to.Add(-s.cfg.Lookback)
	if target.After(wm) {
		if err := s.state.SetWatermark(target); err != nil {
			return err
		}
		wm = target
	}
	if _, err := s.state.Prune(wm.Add(-s.cfg.PruneAfter)); err != nil {
		return err
	}
	return nil
}

// ingestWithRefresh POSTs one batch, transparently refreshing on a single 401.
// The rotated token pair is persisted BEFORE retry so a crash cannot strand the
// daemon on a now-revoked refresh token.
func (s *Syncer) ingestWithRefresh(ctx context.Context, access *string, creds *Creds, chunk []event.Event) (IngestResult, error) {
	res, err := s.client.Ingest(ctx, *access, chunk)
	if !errors.Is(err, ErrUnauthorized) {
		return res, err
	}

	s.refreshMu.Lock()
	defer s.refreshMu.Unlock()

	tp, rerr := s.client.Refresh(ctx, creds.RefreshToken)
	if rerr != nil {
		if errors.Is(rerr, ErrUnauthorized) || isStatus(rerr, 400, 401, 403) {
			return IngestResult{}, ErrReenrollRequired
		}
		return IngestResult{}, rerr
	}
	if err := s.keys.SaveTokens(tp.AccessToken, tp.RefreshToken); err != nil {
		return IngestResult{}, err
	}
	creds.AccessToken, creds.RefreshToken = tp.AccessToken, tp.RefreshToken
	*access = tp.AccessToken
	return s.client.Ingest(ctx, *access, chunk)
}

// isStatus reports whether err is an *APIError with one of the given statuses.
func isStatus(err error, statuses ...int) bool {
	var ae *APIError
	if !errors.As(err, &ae) {
		return false
	}
	for _, st := range statuses {
		if ae.Status == st {
			return true
		}
	}
	return false
}

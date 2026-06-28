package token

import (
	"bytes"
	"context"
	"io"
	"log/slog"
	"os"
	"strings"
	"time"

	"github.com/Aditya1711-tech/cadence/agent/internal/event"
)

// Sink receives a batch of events (in production, an HTTP POST to the daemon's
// loopback /events route). Compatible with collector.NewHTTPSink.
type Sink func([]event.Event) error

// Config tunes the Watcher. Zero values get sensible defaults.
type Config struct {
	MemberID string
	Pricing  *Table
	StateDir string        // where the tail cursor file lives
	Poll     time.Duration // scan cadence (default 30s; token logs are low-frequency)
	Enabled  map[string]bool
	MaxBatch int // events per sink call (default 1000, per ingest convention)
	Logger   *slog.Logger
}

// Watcher detects installed coding-agent tools, incrementally tails their
// session logs, and emits token usage events. It owns no goroutines until Run.
type Watcher struct {
	cfg     Config
	sources []Source
	sink    Sink
	state   *tailState
	parsers map[string]Parser // per-file, for the process lifetime (Codex state)
	warned  map[string]bool   // models already warned-as-unpriced
}

// NewWatcher builds a Watcher over the given sources, loading the tail cursor.
func NewWatcher(sources []Source, sink Sink, cfg Config) (*Watcher, error) {
	if cfg.Poll <= 0 {
		cfg.Poll = 30 * time.Second
	}
	if cfg.MaxBatch <= 0 {
		cfg.MaxBatch = 1000
	}
	if cfg.Pricing == nil {
		cfg.Pricing = DefaultTable()
	}
	if cfg.Logger == nil {
		cfg.Logger = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	if cfg.MemberID == "" {
		cfg.MemberID = "unknown"
	}
	st, err := loadTailState(cursorPath(cfg.StateDir))
	if err != nil {
		return nil, err
	}
	return &Watcher{
		cfg:     cfg,
		sources: sources,
		sink:    sink,
		state:   st,
		parsers: map[string]Parser{},
		warned:  map[string]bool{},
	}, nil
}

// Run scans on the configured cadence until ctx is cancelled. It always returns
// nil (cancellation is normal shutdown).
func (w *Watcher) Run(ctx context.Context) error {
	t := time.NewTicker(w.cfg.Poll)
	defer t.Stop()
	w.scan() // initial pass on startup
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-t.C:
			w.scan()
		}
	}
}

// scan walks every enabled+detected source once, tailing each session file from
// its persisted offset and emitting any new usage events.
func (w *Watcher) scan() {
	for _, src := range w.sources {
		if !w.isEnabled(src.Name()) {
			continue
		}
		root, ok := src.Detect()
		if !ok {
			continue // tool not installed — silent, never an error
		}
		files, err := src.Files(root)
		if err != nil {
			w.cfg.Logger.Warn("token: list files failed", "source", src.Name(), "err", err)
			continue
		}
		for _, file := range files {
			w.tailFile(src, file)
		}
	}
}

func (w *Watcher) tailFile(src Source, file string) {
	off := w.state.get(file)
	data, newOff, ok := readNew(file, off)
	if !ok || len(data) == 0 {
		if newOff != off { // file truncated/rotated -> offset reset; persist it
			w.state.set(file, newOff)
		}
		return
	}

	parser := w.parsers[file]
	if parser == nil {
		parser = src.NewParser()
		w.parsers[file] = parser
	}

	records, err := parser.Parse(data)
	if err != nil {
		w.cfg.Logger.Warn("token: parse failed", "file", file, "err", err)
		return
	}

	var batch []event.Event
	for _, r := range records {
		if !w.cfg.Pricing.Known(r.Model) && !w.warned[r.Model] {
			w.warned[r.Model] = true
			w.cfg.Logger.Warn("token: unknown model, billing at fallback rate", "model", r.Model)
		}
		e, err := r.toEvent(w.cfg.MemberID, w.cfg.Pricing)
		if err != nil {
			w.cfg.Logger.Warn("token: build event failed", "err", err)
			continue
		}
		if err := e.Validate(); err != nil {
			w.cfg.Logger.Warn("token: invalid event skipped", "err", err)
			continue
		}
		batch = append(batch, *e)
	}

	// Only advance the cursor once the events for this chunk are accepted, so a
	// sink failure means we retry the same bytes next scan (no lost events).
	if err := w.emit(batch); err != nil {
		w.cfg.Logger.Warn("token: sink failed; will retry chunk", "file", file, "err", err)
		return
	}
	w.state.set(file, newOff)
	if err := w.state.save(); err != nil {
		w.cfg.Logger.Warn("token: persist cursor failed", "err", err)
	}
	if len(batch) > 0 {
		w.cfg.Logger.Info("token: emitted usage events", "source", src.Name(), "n", len(batch))
	}
}

// emit posts events in <=MaxBatch chunks (ingest accepts <=1000 per call).
func (w *Watcher) emit(events []event.Event) error {
	for len(events) > 0 {
		n := w.cfg.MaxBatch
		if n > len(events) {
			n = len(events)
		}
		if err := w.sink(events[:n]); err != nil {
			return err
		}
		events = events[n:]
	}
	return nil
}

func (w *Watcher) isEnabled(name string) bool {
	if len(w.cfg.Enabled) == 0 {
		return true // no filter => all detected sources
	}
	return w.cfg.Enabled[name]
}

// readNew reads bytes after off up to the last complete line. It returns the
// complete-line data, the new offset (past the consumed lines), and ok=false
// when there's nothing to do. A file shorter than off was truncated/rotated, so
// the offset resets to 0 and the next scan re-reads from the top.
func readNew(file string, off int64) (data []byte, newOff int64, ok bool) {
	f, err := os.Open(file)
	if err != nil {
		return nil, off, false
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil {
		return nil, off, false
	}
	size := fi.Size()
	if off > size {
		return nil, 0, false // rotated/truncated; caller resets cursor to 0
	}
	if off == size {
		return nil, off, false
	}
	if _, err := f.Seek(off, io.SeekStart); err != nil {
		return nil, off, false
	}
	buf := make([]byte, size-off)
	if _, err := io.ReadFull(f, buf); err != nil {
		return nil, off, false
	}
	idx := bytes.LastIndexByte(buf, '\n')
	if idx < 0 {
		return nil, off, false // no complete line yet; wait for more
	}
	return buf[:idx+1], off + int64(idx+1), true
}

func cursorPath(stateDir string) string {
	if stateDir == "" {
		if dir, err := os.UserConfigDir(); err == nil {
			stateDir = dir + string(os.PathSeparator) + "cadence"
		} else {
			stateDir = "."
		}
	}
	return strings.TrimRight(stateDir, "/\\") + string(os.PathSeparator) + "token-cursors.json"
}

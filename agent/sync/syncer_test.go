package cloudsync

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/Aditya1711-tech/cadence/agent/internal/event"
	"github.com/Aditya1711-tech/cadence/agent/internal/keyring"
)

// fakeReader is an in-memory EventReader mirroring the store's [from,to) window.
type fakeReader struct {
	mu  sync.Mutex
	all []event.Event
}

func (r *fakeReader) add(evs ...event.Event) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.all = append(r.all, evs...)
}

func (r *fakeReader) Query(from, to time.Time) ([]event.Event, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	var out []event.Event
	for _, e := range r.all {
		if !e.TsStart.Before(from) && e.TsStart.Before(to) {
			out = append(out, e)
		}
	}
	return out, nil
}

func newTestSyncer(t *testing.T, b *fakeBackend, reader *fakeReader, now time.Time, enroll bool) (*Syncer, *Keystore, *State) {
	t.Helper()
	keys := NewKeystore(keyring.NewMemory(), "svc")
	if enroll {
		if err := keys.SaveEnrollment(Creds{MemberID: "m-1", AccessToken: "a1", RefreshToken: "r1"}); err != nil {
			t.Fatalf("SaveEnrollment: %v", err)
		}
	}
	state := openTestState(t)
	client := NewClient(b.base(), nil)
	s := NewSyncer(reader, state, keys, client, Config{
		Interval: time.Second,
		now:      func() time.Time { return now },
	})
	return s, keys, state
}

func TestRunOnceDrainsAndDedupes(t *testing.T) {
	b := newFakeBackend(t)
	now := time.Date(2026, 6, 27, 12, 0, 0, 0, time.UTC)
	reader := &fakeReader{}
	reader.add(
		mkEvent(t, "a", now.Add(-3*time.Minute)),
		mkEvent(t, "b", now.Add(-2*time.Minute)),
		mkEvent(t, "c", now.Add(-time.Minute)),
	)
	s, _, state := newTestSyncer(t, b, reader, now, true)

	if err := s.RunOnce(context.Background()); err != nil {
		t.Fatalf("RunOnce: %v", err)
	}
	if got := b.receivedCount(); got != 3 {
		t.Fatalf("backend received %d, want 3", got)
	}
	// Watermark advanced to now-Lookback after a clean drain.
	wm, _ := state.Watermark()
	if !wm.Equal(now.Add(-defaultLookback)) {
		t.Fatalf("watermark = %v, want %v", wm, now.Add(-defaultLookback))
	}

	// Second run: everything already synced -> no new ingest call.
	ingestBefore, _, _ := b.calls()
	if err := s.RunOnce(context.Background()); err != nil {
		t.Fatalf("RunOnce (2nd): %v", err)
	}
	ingestAfter, _, _ := b.calls()
	if ingestAfter != ingestBefore {
		t.Fatalf("2nd run made %d new ingest calls, want 0", ingestAfter-ingestBefore)
	}
	if b.receivedCount() != 3 {
		t.Fatalf("received changed to %d after 2nd run", b.receivedCount())
	}
}

func TestRunOnceOfflineThenRecovers(t *testing.T) {
	b := newFakeBackend(t)
	b.set(func(b *fakeBackend) { b.failIngest = true })
	now := time.Date(2026, 6, 27, 12, 0, 0, 0, time.UTC)
	reader := &fakeReader{}
	reader.add(mkEvent(t, "a", now.Add(-time.Minute)), mkEvent(t, "b", now.Add(-30*time.Second)))
	s, _, state := newTestSyncer(t, b, reader, now, true)

	// Offline: cycle fails, nothing delivered, watermark NOT advanced.
	if err := s.RunOnce(context.Background()); err == nil {
		t.Fatal("RunOnce while offline returned nil, want error")
	}
	if b.receivedCount() != 0 {
		t.Fatalf("received %d while offline, want 0", b.receivedCount())
	}
	wm, _ := state.Watermark()
	if !wm.Equal(time.UnixMilli(0).UTC()) {
		t.Fatalf("watermark advanced to %v during outage, want epoch", wm)
	}

	// Back online: the whole backlog flushes.
	b.set(func(b *fakeBackend) { b.failIngest = false })
	if err := s.RunOnce(context.Background()); err != nil {
		t.Fatalf("RunOnce after recovery: %v", err)
	}
	if b.receivedCount() != 2 {
		t.Fatalf("received %d after recovery, want 2", b.receivedCount())
	}
}

func TestRunOnceReactiveRefresh(t *testing.T) {
	b := newFakeBackend(t)
	// Ingest accepts only the post-refresh token, forcing a 401 -> refresh -> retry.
	b.set(func(b *fakeBackend) {
		b.acceptAccess = "a2"
		b.refreshTo = &TokenPair{AccessToken: "a2", RefreshToken: "r2", TokenType: "Bearer", ExpiresInSeconds: 3600}
	})
	now := time.Date(2026, 6, 27, 12, 0, 0, 0, time.UTC)
	reader := &fakeReader{}
	reader.add(mkEvent(t, "a", now.Add(-time.Minute)))
	s, keys, _ := newTestSyncer(t, b, reader, now, true)

	if err := s.RunOnce(context.Background()); err != nil {
		t.Fatalf("RunOnce: %v", err)
	}
	if b.receivedCount() != 1 {
		t.Fatalf("received %d, want 1", b.receivedCount())
	}
	_, refreshCalls, _ := b.calls()
	if refreshCalls != 1 {
		t.Fatalf("refresh calls = %d, want 1", refreshCalls)
	}
	// Rotated tokens were persisted.
	c, _ := keys.Load()
	if c.AccessToken != "a2" || c.RefreshToken != "r2" {
		t.Fatalf("after refresh, stored creds = %+v, want a2/r2", c)
	}
}

func TestRunOnceReenrollRequired(t *testing.T) {
	b := newFakeBackend(t)
	b.set(func(b *fakeBackend) {
		b.acceptAccess = "never" // our token always 401s
		b.refreshStatus = 401    // refresh rejected -> family revoked
	})
	now := time.Date(2026, 6, 27, 12, 0, 0, 0, time.UTC)
	reader := &fakeReader{}
	reader.add(mkEvent(t, "a", now.Add(-time.Minute)))
	s, _, _ := newTestSyncer(t, b, reader, now, true)

	err := s.RunOnce(context.Background())
	if !errors.Is(err, ErrReenrollRequired) {
		t.Fatalf("RunOnce = %v, want ErrReenrollRequired", err)
	}
}

func TestRunOnceNotEnrolled(t *testing.T) {
	b := newFakeBackend(t)
	now := time.Date(2026, 6, 27, 12, 0, 0, 0, time.UTC)
	reader := &fakeReader{}
	reader.add(mkEvent(t, "a", now))
	s, _, _ := newTestSyncer(t, b, reader, now, false) // not enrolled

	if err := s.RunOnce(context.Background()); !errors.Is(err, ErrNotEnrolled) {
		t.Fatalf("RunOnce = %v, want ErrNotEnrolled", err)
	}
	if i, _, _ := b.calls(); i != 0 {
		t.Fatalf("ingest calls = %d while not enrolled, want 0", i)
	}
}

func TestRunOnceBatchesOverMaxBatch(t *testing.T) {
	b := newFakeBackend(t)
	now := time.Date(2026, 6, 27, 12, 0, 0, 0, time.UTC)
	reader := &fakeReader{}
	const n = MaxBatch + 50
	for i := 0; i < n; i++ {
		reader.add(mkEvent(t, "e"+itoa(i), now.Add(-time.Duration(n-i)*time.Second)))
	}
	s, _, _ := newTestSyncer(t, b, reader, now, true)

	if err := s.RunOnce(context.Background()); err != nil {
		t.Fatalf("RunOnce: %v", err)
	}
	if b.receivedCount() != n {
		t.Fatalf("received %d, want %d", b.receivedCount(), n)
	}
	ingestCalls, _, _ := b.calls()
	if ingestCalls != 2 { // 1000 + 50
		t.Fatalf("ingest calls = %d, want 2 (batched at MaxBatch)", ingestCalls)
	}
}

func TestRunStopsOnContextCancel(t *testing.T) {
	b := newFakeBackend(t)
	reader := &fakeReader{}
	s, _, _ := newTestSyncer(t, b, reader, time.Now().UTC(), true)

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { s.Run(ctx); close(done) }()
	cancel()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("Run did not return after context cancel")
	}
}

func itoa(i int) string {
	if i == 0 {
		return "0"
	}
	var buf [20]byte
	p := len(buf)
	for i > 0 {
		p--
		buf[p] = byte('0' + i%10)
		i /= 10
	}
	return string(buf[p:])
}

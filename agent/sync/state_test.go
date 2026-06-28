package cloudsync

import (
	"path/filepath"
	"testing"
	"time"

	"github.com/Aditya1711-tech/cadence/agent/internal/event"
)

func openTestState(t *testing.T) *State {
	t.Helper()
	st, err := OpenState(filepath.Join(t.TempDir(), "sync.db"))
	if err != nil {
		t.Fatalf("OpenState: %v", err)
	}
	t.Cleanup(func() { st.Close() })
	return st
}

// mkEvent builds a minimal valid event at the given start time.
func mkEvent(t *testing.T, id string, ts time.Time) event.Event {
	t.Helper()
	e := event.Event{
		EventID:   id,
		SchemaVer: event.SchemaVersion,
		Source:    event.SourceOS,
		MemberID:  "m1",
		TsStart:   ts.UTC(),
		TsEnd:     ts.UTC().Add(time.Minute),
		App:       "Code",
		Meta:      event.Meta{},
	}
	e.ComputeDuration()
	if err := e.Validate(); err != nil {
		t.Fatalf("invalid test event: %v", err)
	}
	return e
}

func TestWatermarkRoundTrip(t *testing.T) {
	st := openTestState(t)

	wm, err := st.Watermark()
	if err != nil {
		t.Fatalf("Watermark: %v", err)
	}
	if !wm.Equal(time.UnixMilli(0).UTC()) {
		t.Fatalf("first-run watermark = %v, want epoch", wm)
	}

	want := time.Date(2026, 6, 27, 10, 0, 0, 0, time.UTC)
	if err := st.SetWatermark(want); err != nil {
		t.Fatalf("SetWatermark: %v", err)
	}
	got, err := st.Watermark()
	if err != nil {
		t.Fatalf("Watermark: %v", err)
	}
	if !got.Equal(want) {
		t.Fatalf("watermark = %v, want %v", got, want)
	}
}

func TestUnsyncedAndMarkSynced(t *testing.T) {
	st := openTestState(t)
	base := time.Date(2026, 6, 27, 9, 0, 0, 0, time.UTC)
	evs := []event.Event{
		mkEvent(t, "a", base),
		mkEvent(t, "b", base.Add(time.Minute)),
		mkEvent(t, "c", base.Add(2*time.Minute)),
	}

	un, err := st.Unsynced(evs)
	if err != nil {
		t.Fatalf("Unsynced: %v", err)
	}
	if len(un) != 3 {
		t.Fatalf("got %d unsynced, want 3", len(un))
	}

	// Mark the first two synced.
	if err := st.MarkSynced(evs[:2]); err != nil {
		t.Fatalf("MarkSynced: %v", err)
	}
	un, err = st.Unsynced(evs)
	if err != nil {
		t.Fatalf("Unsynced: %v", err)
	}
	if len(un) != 1 || un[0].EventID != "c" {
		t.Fatalf("after mark, unsynced = %v, want [c]", ids(un))
	}

	// Re-marking is idempotent.
	if err := st.MarkSynced(evs[:2]); err != nil {
		t.Fatalf("MarkSynced (repeat): %v", err)
	}
	n, err := st.Count()
	if err != nil {
		t.Fatalf("Count: %v", err)
	}
	if n != 2 {
		t.Fatalf("synced count = %d, want 2", n)
	}
}

func TestPrune(t *testing.T) {
	st := openTestState(t)
	base := time.Date(2026, 6, 27, 9, 0, 0, 0, time.UTC)
	evs := []event.Event{
		mkEvent(t, "old", base),
		mkEvent(t, "new", base.Add(time.Hour)),
	}
	if err := st.MarkSynced(evs); err != nil {
		t.Fatalf("MarkSynced: %v", err)
	}
	// Prune everything at/below base+30m -> removes "old", keeps "new".
	removed, err := st.Prune(base.Add(30 * time.Minute))
	if err != nil {
		t.Fatalf("Prune: %v", err)
	}
	if removed != 1 {
		t.Fatalf("pruned %d, want 1", removed)
	}
	un, _ := st.Unsynced(evs)
	if len(un) != 1 || un[0].EventID != "old" {
		t.Fatalf("after prune, unsynced = %v, want [old]", ids(un))
	}
}

// events2 returns two valid events for HTTP-layer tests.
func events2(t *testing.T) []event.Event {
	t.Helper()
	base := time.Date(2026, 6, 27, 9, 0, 0, 0, time.UTC)
	return []event.Event{mkEvent(t, "e1", base), mkEvent(t, "e2", base.Add(time.Minute))}
}

func ids(evs []event.Event) []string {
	out := make([]string, len(evs))
	for i, e := range evs {
		out[i] = e.EventID
	}
	return out
}

package store

import (
	"bytes"
	"crypto/rand"
	"database/sql"
	"io"
	"path/filepath"
	"testing"
	"time"

	"github.com/Aditya1711-tech/cadence/agent/internal/crypto"
	"github.com/Aditya1711-tech/cadence/agent/internal/event"
)

func tempKey(t *testing.T) []byte {
	t.Helper()
	k := make([]byte, crypto.KeySize)
	if _, err := io.ReadFull(rand.Reader, k); err != nil {
		t.Fatalf("gen key: %v", err)
	}
	return k
}

func openStore(t *testing.T, key []byte) (*Store, string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "cadence.db")
	s, err := Open(path, key)
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	t.Cleanup(func() { s.Close() })
	return s, path
}

func sampleEvent(t *testing.T, start time.Time) *event.Event {
	t.Helper()
	e, err := event.New(event.SourceVSCode, "member-1", start, start.Add(283*time.Second), "Visual Studio Code")
	if err != nil {
		t.Fatalf("new event: %v", err)
	}
	e.Title = event.Ptr("auth.ts — cadence-api")
	e.Project = event.Ptr("cadence-api")
	e.Category = event.Ptr(event.CategoryDeepWork)
	e.Meta = event.Meta{event.MetaLang: "typescript"}
	return e
}

func TestAppendThenQueryRoundTrip(t *testing.T) {
	key := tempKey(t)
	s, _ := openStore(t, key)
	start := time.Date(2025, 6, 1, 9, 14, 2, 0, time.UTC)
	want := sampleEvent(t, start)
	if err := s.Append(want); err != nil {
		t.Fatalf("append: %v", err)
	}

	got, err := s.Query(start.Add(-time.Hour), start.Add(time.Hour))
	if err != nil {
		t.Fatalf("query: %v", err)
	}
	if len(got) != 1 {
		t.Fatalf("got %d events, want 1", len(got))
	}
	g := got[0]
	if g.EventID != want.EventID || g.Source != want.Source || g.App != want.App {
		t.Errorf("scalar fields mismatch: %+v", g)
	}
	if g.Title == nil || *g.Title != *want.Title {
		t.Errorf("title not round-tripped: %v", g.Title)
	}
	if g.Project == nil || *g.Project != "cadence-api" {
		t.Errorf("project mismatch: %v", g.Project)
	}
	if g.Category == nil || *g.Category != event.CategoryDeepWork {
		t.Errorf("category mismatch: %v", g.Category)
	}
	if g.Meta[event.MetaLang] != "typescript" {
		t.Errorf("meta not round-tripped: %v", g.Meta)
	}
	if g.URL != nil {
		t.Errorf("url should be nil, got %v", g.URL)
	}
	if !g.TsStart.Equal(want.TsStart) || !g.TsEnd.Equal(want.TsEnd) {
		t.Errorf("timestamps mismatch: %v..%v", g.TsStart, g.TsEnd)
	}
	if g.DurationMs != 283000 {
		t.Errorf("duration mismatch: %d", g.DurationMs)
	}
}

func TestAppendIsIdempotent(t *testing.T) {
	s, _ := openStore(t, tempKey(t))
	start := time.Date(2025, 6, 1, 10, 0, 0, 0, time.UTC)
	e := sampleEvent(t, start)
	for i := 0; i < 3; i++ {
		if err := s.Append(e); err != nil {
			t.Fatalf("append %d: %v", i, err)
		}
	}
	n, err := s.Count()
	if err != nil {
		t.Fatalf("count: %v", err)
	}
	if n != 1 {
		t.Fatalf("expected 1 row after idempotent re-append, got %d", n)
	}
}

func TestQueryTimeRangeFiltersAndOrders(t *testing.T) {
	s, _ := openStore(t, tempKey(t))
	base := time.Date(2025, 6, 1, 0, 0, 0, 0, time.UTC)
	// insert out of order at +2h, +0h, +1h
	for _, off := range []time.Duration{2 * time.Hour, 0, time.Hour} {
		if err := s.Append(sampleEvent(t, base.Add(off))); err != nil {
			t.Fatalf("append: %v", err)
		}
	}
	// window [base, base+90m) should include +0h and +1h, exclude +2h
	got, err := s.Query(base, base.Add(90*time.Minute))
	if err != nil {
		t.Fatalf("query: %v", err)
	}
	if len(got) != 2 {
		t.Fatalf("expected 2 events in window, got %d", len(got))
	}
	if !got[0].TsStart.Equal(base) || !got[1].TsStart.Equal(base.Add(time.Hour)) {
		t.Errorf("results not ascending by ts_start: %v, %v", got[0].TsStart, got[1].TsStart)
	}
}

func TestNullableTitleAndUrl(t *testing.T) {
	s, _ := openStore(t, tempKey(t))
	start := time.Date(2025, 6, 1, 11, 0, 0, 0, time.UTC)
	e := sampleEvent(t, start)
	e.Title = nil // redacted-out / unavailable
	e.URL = nil
	if err := s.Append(e); err != nil {
		t.Fatalf("append: %v", err)
	}
	got, err := s.Query(start.Add(-time.Minute), start.Add(time.Minute))
	if err != nil {
		t.Fatalf("query: %v", err)
	}
	if got[0].Title != nil || got[0].URL != nil {
		t.Errorf("expected nil title/url, got %v / %v", got[0].Title, got[0].URL)
	}
}

// TestEncryptedAtRest verifies the sensitive columns are not stored in plaintext.
func TestEncryptedAtRest(t *testing.T) {
	key := tempKey(t)
	path := filepath.Join(t.TempDir(), "cadence.db")
	s, err := Open(path, key)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	secretTitle := "TOP-SECRET-WINDOW-TITLE"
	e := sampleEvent(t, time.Date(2025, 6, 1, 12, 0, 0, 0, time.UTC))
	e.Title = event.Ptr(secretTitle)
	e.Meta = event.Meta{"repo": "org/super-secret-repo"}
	if err := s.Append(e); err != nil {
		t.Fatalf("append: %v", err)
	}
	s.Close()

	raw, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatalf("raw open: %v", err)
	}
	defer raw.Close()
	var titleEnc, metaEnc []byte
	if err := raw.QueryRow(`SELECT title_enc, meta_enc FROM events LIMIT 1`).Scan(&titleEnc, &metaEnc); err != nil {
		t.Fatalf("raw scan: %v", err)
	}
	if bytes.Contains(titleEnc, []byte(secretTitle)) {
		t.Error("title stored in plaintext at rest")
	}
	if bytes.Contains(metaEnc, []byte("super-secret-repo")) {
		t.Error("meta stored in plaintext at rest")
	}
}

// TestWrongKeyCannotDecrypt verifies opening the store with a different key
// fails to decrypt existing rows.
func TestWrongKeyCannotDecrypt(t *testing.T) {
	key := tempKey(t)
	path := filepath.Join(t.TempDir(), "cadence.db")
	s, err := Open(path, key)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	start := time.Date(2025, 6, 1, 13, 0, 0, 0, time.UTC)
	if err := s.Append(sampleEvent(t, start)); err != nil {
		t.Fatalf("append: %v", err)
	}
	s.Close()

	wrong, err := Open(path, tempKey(t))
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	defer wrong.Close()
	if _, err := wrong.Query(start.Add(-time.Hour), start.Add(time.Hour)); err == nil {
		t.Fatal("expected decryption failure with wrong key, got nil")
	}
}

func TestOpenRejectsBadKey(t *testing.T) {
	path := filepath.Join(t.TempDir(), "cadence.db")
	if _, err := Open(path, []byte("too-short")); err != crypto.ErrKeySize {
		t.Fatalf("expected ErrKeySize, got %v", err)
	}
}

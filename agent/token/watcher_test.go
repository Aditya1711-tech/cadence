package token

import (
	"os"
	"path/filepath"
	"sync"
	"testing"

	"github.com/Aditya1711-tech/cadence/agent/internal/event"
)

// fakeSource serves a single fixed file as a Claude Code source rooted at dir.
type fakeSource struct{ file string }

func (fakeSource) Name() string                     { return "claude_code" }
func (s fakeSource) Detect() (string, bool)         { return filepath.Dir(s.file), true }
func (s fakeSource) Files(string) ([]string, error) { return []string{s.file}, nil }
func (fakeSource) NewParser() Parser                { return ClaudeCodeParser{} }

func ccLineJSON(in, out int64) string {
	return `{"type":"assistant","timestamp":"2026-06-27T09:57:53.731Z","cwd":"c:\\learn\\cadence","message":{"model":"claude-opus-4-8","content":[{"type":"text","text":"secret"}],"usage":{"input_tokens":` +
		itoa(in) + `,"output_tokens":` + itoa(out) + `}}}` + "\n"
}

func itoa(n int64) string {
	if n == 0 {
		return "0"
	}
	var b []byte
	for n > 0 {
		b = append([]byte{byte('0' + n%10)}, b...)
		n /= 10
	}
	return string(b)
}

func TestWatcherIncrementalTailAndCursor(t *testing.T) {
	dir := t.TempDir()
	logFile := filepath.Join(dir, "session.jsonl")
	if err := os.WriteFile(logFile, []byte(ccLineJSON(100, 10)), 0o600); err != nil {
		t.Fatal(err)
	}

	var mu sync.Mutex
	var got []event.Event
	sink := func(evs []event.Event) error {
		mu.Lock()
		got = append(got, evs...)
		mu.Unlock()
		return nil
	}

	stateDir := filepath.Join(dir, "state")
	w, err := NewWatcher([]Source{fakeSource{file: logFile}}, sink, Config{
		MemberID: "m1",
		StateDir: stateDir,
	})
	if err != nil {
		t.Fatal(err)
	}

	w.scan() // first pass: one event
	if len(got) != 1 {
		t.Fatalf("after first scan got %d events, want 1", len(got))
	}

	w.scan() // no new bytes: still one event (cursor held)
	if len(got) != 1 {
		t.Fatalf("re-scan double-counted: got %d events, want 1", len(got))
	}

	// Append a second usage line; only the new line should be emitted.
	f, err := os.OpenFile(logFile, os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.WriteString(ccLineJSON(200, 20)); err != nil {
		t.Fatal(err)
	}
	f.Close()

	w.scan()
	if len(got) != 2 {
		t.Fatalf("after append got %d events, want 2", len(got))
	}

	// Cursor persisted: a fresh watcher resumes at EOF and emits nothing new.
	got = nil
	w2, err := NewWatcher([]Source{fakeSource{file: logFile}}, sink, Config{MemberID: "m1", StateDir: stateDir})
	if err != nil {
		t.Fatal(err)
	}
	w2.scan()
	if len(got) != 0 {
		t.Fatalf("fresh watcher reparsed from persisted cursor: got %d events, want 0", len(got))
	}
}

func TestWatcherSinkFailureRetriesChunk(t *testing.T) {
	dir := t.TempDir()
	logFile := filepath.Join(dir, "session.jsonl")
	if err := os.WriteFile(logFile, []byte(ccLineJSON(100, 10)), 0o600); err != nil {
		t.Fatal(err)
	}
	fail := true
	var delivered int
	sink := func(evs []event.Event) error {
		if fail {
			return errSink
		}
		delivered += len(evs)
		return nil
	}
	w, err := NewWatcher([]Source{fakeSource{file: logFile}}, sink, Config{MemberID: "m1", StateDir: filepath.Join(dir, "s")})
	if err != nil {
		t.Fatal(err)
	}
	w.scan() // sink fails -> cursor not advanced
	fail = false
	w.scan() // retries same bytes -> delivered now
	if delivered != 1 {
		t.Fatalf("expected 1 event delivered on retry, got %d", delivered)
	}
}

type sinkErr struct{}

func (sinkErr) Error() string { return "sink down" }

var errSink = sinkErr{}

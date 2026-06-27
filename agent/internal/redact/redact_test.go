package redact

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Aditya1711-tech/cadence/agent/internal/event"
)

func TestApplyHashesMatches(t *testing.T) {
	r, err := New([]string{`(?i)secret-project`, `https://internal\.corp`})
	if err != nil {
		t.Fatalf("new: %v", err)
	}
	e := &event.Event{
		Title: event.Ptr("Secret-Project roadmap.md"),
		URL:   event.Ptr("https://internal.corp/wiki/x"),
	}
	r.Apply(e)
	if e.Title == nil || !strings.HasPrefix(*e.Title, Prefix) {
		t.Errorf("title not redacted: %v", e.Title)
	}
	if e.URL == nil || !strings.HasPrefix(*e.URL, Prefix) {
		t.Errorf("url not redacted: %v", e.URL)
	}
	if strings.Contains(*e.Title, "Secret-Project") {
		t.Errorf("plaintext leaked in redacted title: %v", *e.Title)
	}
}

func TestApplyLeavesNonMatches(t *testing.T) {
	r, _ := New([]string{`secret`})
	e := &event.Event{
		Title: event.Ptr("auth.ts — cadence-api"),
		URL:   event.Ptr("https://pkg.go.dev"),
	}
	r.Apply(e)
	if *e.Title != "auth.ts — cadence-api" {
		t.Errorf("non-matching title was altered: %v", *e.Title)
	}
	if *e.URL != "https://pkg.go.dev" {
		t.Errorf("non-matching url was altered: %v", *e.URL)
	}
}

func TestHashIsStable(t *testing.T) {
	a := Hash("the same secret")
	b := Hash("the same secret")
	c := Hash("a different secret")
	if a != b {
		t.Errorf("hash not stable: %q != %q", a, b)
	}
	if a == c {
		t.Errorf("distinct inputs collided: %q", a)
	}
}

func TestNilAndEmptyAreNoOps(t *testing.T) {
	var r *Redactor // nil
	e := &event.Event{Title: event.Ptr("anything")}
	r.Apply(e) // must not panic
	if *e.Title != "anything" {
		t.Errorf("nil redactor altered title")
	}
	empty, _ := New(nil)
	if empty.Enabled() {
		t.Errorf("empty redactor should be disabled")
	}
	empty.Apply(e)
	if *e.Title != "anything" {
		t.Errorf("empty redactor altered title")
	}
}

func TestNilTitleURL(t *testing.T) {
	r, _ := New([]string{`x`})
	e := &event.Event{} // nil Title and URL
	r.Apply(e)          // must not panic
	if e.Title != nil || e.URL != nil {
		t.Errorf("nil fields became non-nil")
	}
}

func TestBadPattern(t *testing.T) {
	if _, err := New([]string{`(`}); err == nil {
		t.Error("expected error for invalid regex")
	}
}

func TestLoadRoundTrip(t *testing.T) {
	b, err := DefaultConfigJSON()
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	path := filepath.Join(t.TempDir(), "redact.json")
	if err := os.WriteFile(path, b, 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}
	r, err := Load(path)
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	if r.Enabled() {
		t.Errorf("default config should be empty/disabled")
	}

	custom := `{"patterns":["password","api[_-]?key"]}`
	if err := os.WriteFile(path, []byte(custom), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}
	r2, err := Load(path)
	if err != nil {
		t.Fatalf("load custom: %v", err)
	}
	if !r2.Matches("my api-key is here") {
		t.Errorf("custom pattern did not match")
	}
}

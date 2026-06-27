package classify

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/Aditya1711-tech/cadence/agent/internal/event"
)

func ev(src event.Source, app, title, url string, idle bool) *event.Event {
	e := &event.Event{Source: src, App: app, IsIdle: idle}
	if title != "" {
		e.Title = event.Ptr(title)
	}
	if url != "" {
		e.URL = event.Ptr(url)
	}
	return e
}

func TestDefaultClassification(t *testing.T) {
	c := Default()
	cases := []struct {
		name string
		e    *event.Event
		want event.Category
	}{
		{"vscode source", ev(event.SourceVSCode, "Visual Studio Code", "auth.ts", "", false), event.CategoryDeepWork},
		{"os editor app", ev(event.SourceOS, "GoLand", "main.go", "", false), event.CategoryDeepWork},
		{"terminal", ev(event.SourceOS, "iTerm", "zsh", "", false), event.CategoryDeepWork},
		{"zoom app", ev(event.SourceOS, "zoom.us", "Meeting", "", false), event.CategoryMeetings},
		{"google meet url", ev(event.SourceChrome, "Google Chrome", "Meet", "https://meet.google.com/abc-defg", false), event.CategoryMeetings},
		{"slack app", ev(event.SourceOS, "Slack", "general", "", false), event.CategoryComms},
		{"gmail url", ev(event.SourceChrome, "Google Chrome", "Inbox", "https://mail.google.com/mail/u/0", false), event.CategoryComms},
		{"github pr is review not research", ev(event.SourceChrome, "Google Chrome", "PR", "https://github.com/org/repo/pull/42", false), event.CategoryCodeReview},
		{"github home is research", ev(event.SourceChrome, "Google Chrome", "GH", "https://github.com/org/repo", false), event.CategoryResearch},
		{"token source is ai", ev(event.SourceToken, "Claude Code", "", "", false), event.CategoryAIAssisted},
		{"claude.ai url is ai", ev(event.SourceChrome, "Google Chrome", "Claude", "https://claude.ai/chat", false), event.CategoryAIAssisted},
		{"plain browsing is research", ev(event.SourceChrome, "Firefox", "docs", "https://pkg.go.dev/net/http", false), event.CategoryResearch},
		{"idle beats everything", ev(event.SourceVSCode, "Visual Studio Code", "auth.ts", "", true), event.CategoryIdle},
		{"unknown app is other", ev(event.SourceOS, "Some Random App", "x", "", false), event.CategoryOther},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := c.Classify(tc.e); got != tc.want {
				t.Errorf("Classify = %q, want %q", got, tc.want)
			}
		})
	}
}

func TestApplySetsCategory(t *testing.T) {
	c := Default()
	e := ev(event.SourceVSCode, "Visual Studio Code", "auth.ts", "", false)
	c.Apply(e)
	if e.Category == nil || *e.Category != event.CategoryDeepWork {
		t.Fatalf("Apply did not set deep_work: %v", e.Category)
	}
}

func TestDefaultRulesetJSONRoundTrips(t *testing.T) {
	b, err := DefaultRulesetJSON()
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	path := filepath.Join(t.TempDir(), "rules.json")
	if err := os.WriteFile(path, b, 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}
	c, err := Load(path)
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	// behaves identically to the built-in default on a sample
	if got := c.Classify(ev(event.SourceOS, "Slack", "", "", false)); got != event.CategoryComms {
		t.Errorf("loaded ruleset misclassified slack: %q", got)
	}
}

func TestLoadUserOverride(t *testing.T) {
	path := filepath.Join(t.TempDir(), "rules.json")
	custom := `{
      "default_category":"research",
      "rules":[
        {"name":"my-editor","category":"deep_work","app":"myeditor"},
        {"name":"focus-title","category":"deep_work","title":"FOCUS"}
      ]
    }`
	if err := os.WriteFile(path, []byte(custom), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}
	c, err := Load(path)
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	if got := c.Classify(ev(event.SourceOS, "MyEditor", "", "", false)); got != event.CategoryDeepWork {
		t.Errorf("custom app rule failed: %q", got)
	}
	if got := c.Classify(ev(event.SourceOS, "whatever", "do not focus here", "", false)); got != event.CategoryDeepWork {
		t.Errorf("custom title rule (case-insensitive) failed: %q", got)
	}
	if got := c.Classify(ev(event.SourceOS, "unmatched", "", "", false)); got != event.CategoryResearch {
		t.Errorf("custom default failed: %q", got)
	}
}

func TestNewRejectsInvalid(t *testing.T) {
	if _, err := New(Ruleset{DefaultCategory: "bogus"}); err == nil {
		t.Error("expected error for invalid default category")
	}
	if _, err := New(Ruleset{Rules: []Rule{{Name: "x", Category: "nope"}}}); err == nil {
		t.Error("expected error for invalid rule category")
	}
	if _, err := New(Ruleset{Rules: []Rule{{Name: "x", Category: event.CategoryOther, App: "("}}}); err == nil {
		t.Error("expected error for bad regexp")
	}
	if _, err := New(Ruleset{Rules: []Rule{{Name: "x", Category: event.CategoryOther, Sources: []event.Source{"slack"}}}}); err == nil {
		t.Error("expected error for invalid source")
	}
}

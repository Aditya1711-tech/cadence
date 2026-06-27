// Package classify maps an event's app/title/url (and source/idle) to a
// Category using an ordered ruleset — the first matching rule wins, else the
// ruleset's default category.
//
// Matching is case-insensitive; app/title/url patterns are regular expressions.
// A rule constrains on any subset of {source, app, title, url, is_idle}; all of
// the fields it sets must match (AND). A rule that sets none matches everything
// (useful only as an explicit catch-all). The built-in DefaultRuleset covers the
// common cases (editors→deep_work, meetings, comms, code review, AI, research);
// users can override it with a JSON file via Load.
package classify

import (
	"encoding/json"
	"fmt"
	"os"
	"regexp"

	"github.com/Aditya1711-tech/cadence/agent/internal/event"
)

// Rule is one classification rule as written in the JSON ruleset file.
type Rule struct {
	Name     string         `json:"name"`
	Category event.Category `json:"category"`
	Sources  []event.Source `json:"source,omitempty"`
	App      string         `json:"app,omitempty"`
	Title    string         `json:"title,omitempty"`
	URL      string         `json:"url,omitempty"`
	IsIdle   *bool          `json:"is_idle,omitempty"`
}

// Ruleset is the serializable classifier configuration.
type Ruleset struct {
	DefaultCategory event.Category `json:"default_category"`
	Rules           []Rule         `json:"rules"`
}

// Classifier is a compiled, ready-to-use ruleset.
type Classifier struct {
	def   event.Category
	rules []compiledRule
}

type compiledRule struct {
	category event.Category
	sources  []event.Source
	app      *regexp.Regexp
	title    *regexp.Regexp
	url      *regexp.Regexp
	isIdle   *bool
}

// New compiles and validates a Ruleset into a Classifier.
func New(rs Ruleset) (*Classifier, error) {
	def := rs.DefaultCategory
	if def == "" {
		def = event.CategoryOther
	}
	if !def.IsValid() {
		return nil, fmt.Errorf("classify: default_category %q is invalid", def)
	}
	c := &Classifier{def: def}
	for i, r := range rs.Rules {
		if !r.Category.IsValid() {
			return nil, fmt.Errorf("classify: rule %d (%q): category %q is invalid", i, r.Name, r.Category)
		}
		for _, s := range r.Sources {
			if !s.IsValid() {
				return nil, fmt.Errorf("classify: rule %d (%q): source %q is invalid", i, r.Name, s)
			}
		}
		cr := compiledRule{category: r.Category, sources: r.Sources, isIdle: r.IsIdle}
		var err error
		if cr.app, err = compile(r.App); err != nil {
			return nil, fmt.Errorf("classify: rule %d (%q) app: %w", i, r.Name, err)
		}
		if cr.title, err = compile(r.Title); err != nil {
			return nil, fmt.Errorf("classify: rule %d (%q) title: %w", i, r.Name, err)
		}
		if cr.url, err = compile(r.URL); err != nil {
			return nil, fmt.Errorf("classify: rule %d (%q) url: %w", i, r.Name, err)
		}
		c.rules = append(c.rules, cr)
	}
	return c, nil
}

// compile builds a case-insensitive regexp, or nil for an empty pattern.
func compile(pattern string) (*regexp.Regexp, error) {
	if pattern == "" {
		return nil, nil
	}
	return regexp.Compile("(?i)" + pattern)
}

// Load reads a JSON ruleset from path and compiles it.
func Load(path string) (*Classifier, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("classify: read ruleset: %w", err)
	}
	var rs Ruleset
	if err := json.Unmarshal(b, &rs); err != nil {
		return nil, fmt.Errorf("classify: parse ruleset: %w", err)
	}
	return New(rs)
}

// Default returns a Classifier built from DefaultRuleset. It panics only on a
// programming error in the built-in ruleset (covered by tests).
func Default() *Classifier {
	c, err := New(DefaultRuleset())
	if err != nil {
		panic("classify: built-in ruleset is invalid: " + err.Error())
	}
	return c
}

// Classify returns the category for e: the first matching rule's category, or
// the default if none match.
func (c *Classifier) Classify(e *event.Event) event.Category {
	for i := range c.rules {
		if c.rules[i].match(e) {
			return c.rules[i].category
		}
	}
	return c.def
}

// Apply sets e.Category to the classified value.
func (c *Classifier) Apply(e *event.Event) {
	e.Category = event.Ptr(c.Classify(e))
}

func (r *compiledRule) match(e *event.Event) bool {
	if r.isIdle != nil && *r.isIdle != e.IsIdle {
		return false
	}
	if len(r.sources) > 0 && !containsSource(r.sources, e.Source) {
		return false
	}
	if r.app != nil && !r.app.MatchString(e.App) {
		return false
	}
	if r.title != nil && (e.Title == nil || !r.title.MatchString(*e.Title)) {
		return false
	}
	if r.url != nil && (e.URL == nil || !r.url.MatchString(*e.URL)) {
		return false
	}
	return true
}

func containsSource(ss []event.Source, s event.Source) bool {
	for _, x := range ss {
		if x == s {
			return true
		}
	}
	return false
}

// DefaultRulesetJSON returns the built-in ruleset as indented JSON, for writing
// a starter file users can edit.
func DefaultRulesetJSON() ([]byte, error) {
	return json.MarshalIndent(DefaultRuleset(), "", "  ")
}

func boolPtr(b bool) *bool { return &b }

// DefaultRuleset is the shipped ruleset. Order matters: specific signals
// (idle, meetings, AI, code review, comms) are matched before the broad
// editor→deep_work and browser→research fallbacks.
func DefaultRuleset() Ruleset {
	return Ruleset{
		DefaultCategory: event.CategoryOther,
		Rules: []Rule{
			{Name: "idle", Category: event.CategoryIdle, IsIdle: boolPtr(true)},

			{Name: "meetings-url", Category: event.CategoryMeetings,
				URL: `zoom\.us|meet\.google\.com|teams\.(microsoft|live)\.com|whereby\.com|webex\.com|around\.co`},
			{Name: "meetings-app", Category: event.CategoryMeetings,
				App: `zoom|microsoft teams|webex|google meet|around`},

			{Name: "ai-token-source", Category: event.CategoryAIAssisted,
				Sources: []event.Source{event.SourceToken}},
			{Name: "ai-url", Category: event.CategoryAIAssisted,
				URL: `claude\.ai|chatgpt\.com|chat\.openai\.com|gemini\.google\.com|copilot\.microsoft\.com|perplexity\.ai`},

			{Name: "code-review-url", Category: event.CategoryCodeReview,
				URL: `github\.com/.+/(pull|commit)|gitlab\.com/.+/(merge_requests|commit)|bitbucket\.org/.+/pull-requests`},

			{Name: "comms-app", Category: event.CategoryComms,
				App: `slack|discord|microsoft outlook|^mail$|thunderbird|telegram|messages|spark`},
			{Name: "comms-url", Category: event.CategoryComms,
				URL: `mail\.google\.com|outlook\.(office|live)\.com|slack\.com|discord\.com|web\.whatsapp\.com|web\.telegram\.org`},

			{Name: "editor-source", Category: event.CategoryDeepWork,
				Sources: []event.Source{event.SourceVSCode}},
			{Name: "editor-app", Category: event.CategoryDeepWork,
				App: `visual studio code|vscodium|goland|intellij|pycharm|webstorm|phpstorm|clion|rider|datagrip|rubymine|android studio|xcode|sublime text|neovim|nvim|vim|emacs|zed|cursor|windsurf|fleet|terminal|iterm|alacritty|wezterm|warp|kitty`},

			{Name: "research-browser-source", Category: event.CategoryResearch,
				Sources: []event.Source{event.SourceChrome}},
			{Name: "research-browser-app", Category: event.CategoryResearch,
				App: `google chrome|chromium|firefox|safari|microsoft edge|arc|brave|opera|vivaldi`},
		},
	}
}

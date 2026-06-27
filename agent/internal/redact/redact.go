// Package redact applies the user's local redaction list: titles or URLs that
// match a user-controlled regex are replaced with a stable hash BEFORE the
// event is stored or ever leaves the device (see docs/00-SYSTEM-KNOWLEDGE.md
// §8). Hashing (not dropping) keeps redacted values groupable — the same secret
// title always yields the same token — while hiding the content itself.
//
// Redaction runs AFTER classification, so categories are still derived from the
// real app/title/url; only the stored/synced text is masked.
package redact

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"regexp"

	"github.com/Aditya1711-tech/cadence/agent/internal/event"
)

// Prefix marks a redacted value so consumers can recognize it.
const Prefix = "redacted:sha256:"

// Redactor masks event fields matching any of its patterns.
type Redactor struct {
	pats []*regexp.Regexp
}

// Config is the serializable redaction list.
type Config struct {
	Patterns []string `json:"patterns"`
}

// New compiles the given regex patterns into a Redactor. Patterns are used
// as-is (add "(?i)" yourself for case-insensitivity). An empty list yields a
// no-op Redactor.
func New(patterns []string) (*Redactor, error) {
	r := &Redactor{}
	for i, p := range patterns {
		re, err := regexp.Compile(p)
		if err != nil {
			return nil, fmt.Errorf("redact: pattern %d (%q): %w", i, p, err)
		}
		r.pats = append(r.pats, re)
	}
	return r, nil
}

// Load reads a JSON {"patterns":[...]} file and compiles it.
func Load(path string) (*Redactor, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("redact: read list: %w", err)
	}
	var c Config
	if err := json.Unmarshal(b, &c); err != nil {
		return nil, fmt.Errorf("redact: parse list: %w", err)
	}
	return New(c.Patterns)
}

// Enabled reports whether any pattern is configured.
func (r *Redactor) Enabled() bool { return r != nil && len(r.pats) > 0 }

// Matches reports whether s matches any redaction pattern.
func (r *Redactor) Matches(s string) bool {
	if r == nil {
		return false
	}
	for _, re := range r.pats {
		if re.MatchString(s) {
			return true
		}
	}
	return false
}

// Apply masks e.Title and e.URL in place if they match any pattern. A nil
// Redactor or empty list is a no-op.
func (r *Redactor) Apply(e *event.Event) {
	if !r.Enabled() {
		return
	}
	if e.Title != nil && r.Matches(*e.Title) {
		e.Title = event.Ptr(Hash(*e.Title))
	}
	if e.URL != nil && r.Matches(*e.URL) {
		e.URL = event.Ptr(Hash(*e.URL))
	}
}

// Hash returns the stable redaction token for s.
func Hash(s string) string {
	sum := sha256.Sum256([]byte(s))
	return Prefix + hex.EncodeToString(sum[:8]) // 64-bit prefix is plenty for grouping
}

// DefaultConfigJSON returns an empty starter list for users to populate.
func DefaultConfigJSON() ([]byte, error) {
	return json.MarshalIndent(Config{Patterns: []string{}}, "", "  ")
}

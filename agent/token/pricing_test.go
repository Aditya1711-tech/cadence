package token

import (
	"math"
	"os"
	"path/filepath"
	"testing"
)

func approx(a, b float64) bool { return math.Abs(a-b) < 1e-9 }

func TestCostAnthropic(t *testing.T) {
	tbl := DefaultTable()
	// Opus: input 6126, output 830, cache_write 3041, cache_read 20837.
	u := Usage{Input: 6126, Output: 830, CacheWrite: 3041, CacheRead: 20837}
	got := tbl.CostAnthropic("claude-opus-4-8", u)
	want := (6126*5.0 + 830*25.0 + 3041*6.25 + 20837*0.5) / 1e6
	if !approx(got, want) {
		t.Fatalf("opus cost = %v, want %v", got, want)
	}
}

func TestCostOpenAI_CachedIsSubset(t *testing.T) {
	tbl := DefaultTable()
	// cached_input is a SUBSET of input: uncached = 10200-8448 = 1752.
	u := Usage{Input: 10200, Output: 58, CachedInput: 8448}
	got := tbl.CostOpenAI("gpt-5-codex", u)
	want := (1752*1.25 + 8448*0.125 + 58*10.0) / 1e6
	if !approx(got, want) {
		t.Fatalf("codex cost = %v, want %v", got, want)
	}
}

func TestNormalizeModelFamilies(t *testing.T) {
	cases := map[string]string{
		"claude-opus-4-8":          "opus",
		"claude-opus-4-5-20251101": "opus",
		"claude-sonnet-4-6":        "sonnet",
		"claude-3-5-haiku":         "haiku",
		"claude-fable-5":           "fable",
		"gpt-5-codex":              "gpt-5",
		"o3-mini":                  "o-series",
		"weird-model":              "weird-model",
	}
	for in, want := range cases {
		if got := normalizeModel(in); got != want {
			t.Errorf("normalizeModel(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestUnknownModelUsesFallbackAndFlagged(t *testing.T) {
	tbl := DefaultTable()
	if tbl.Known("totally-new-model") {
		t.Fatal("expected unknown model to be !Known")
	}
	// Fallback applies (opus-equivalent rates), so cost is non-zero, not silently 0.
	if c := tbl.CostAnthropic("totally-new-model", Usage{Input: 1000}); c <= 0 {
		t.Fatalf("expected fallback cost > 0, got %v", c)
	}
}

func TestLoadTableOverlay(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "pricing.json")
	if err := os.WriteFile(p, []byte(`{"models":{"opus":{"input":7,"output":30,"cache_write":8,"cache_read":1}}}`), 0o600); err != nil {
		t.Fatal(err)
	}
	tbl, err := LoadTable(p)
	if err != nil {
		t.Fatal(err)
	}
	// Overridden opus rate applies; untouched families keep defaults.
	if c := tbl.CostAnthropic("claude-opus-4-8", Usage{Input: 1_000_000}); !approx(c, 7) {
		t.Fatalf("overridden opus input cost = %v, want 7", c)
	}
	if c := tbl.CostAnthropic("claude-haiku-4-5", Usage{Input: 1_000_000}); !approx(c, 1) {
		t.Fatalf("default haiku input cost = %v, want 1", c)
	}
}

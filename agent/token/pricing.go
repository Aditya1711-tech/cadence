// Package token is the AI coding-agent token watcher (stream P2-C). It tails the
// local session logs of installed coding-agent CLIs, extracts ONLY usage numbers
// (model, token counts, timestamps — never prompt/response text; see
// docs/P2-C.2-counts-only-privacy.md), computes a USD cost from a per-model price
// table, and emits Event Contract events with source:"token" to the daemon's
// loopback /events route like every other collector.
//
// The two source logs lack a cost field (verified, see docs/P2-C.1), so cost is
// always computed here from token counts × per-model rates.
package token

import (
	"encoding/json"
	"fmt"
	"os"
	"strings"
)

// Rates holds per-million-token USD prices for one model. The four tiers exist
// because the two vendors bill cache differently (see Cost methods below).
type Rates struct {
	InputPerMTok      float64 `json:"input"`       // uncached input tokens
	OutputPerMTok     float64 `json:"output"`      // output tokens
	CacheWritePerMTok float64 `json:"cache_write"` // Anthropic 5m cache-creation; 0 for OpenAI
	CacheReadPerMTok  float64 `json:"cache_read"`  // cache-read (Anthropic) / cached input (OpenAI)
}

// Usage is the vendor-neutral set of counts a parser extracts from one model
// turn. Cache semantics differ by vendor and are resolved by the Cost* methods,
// not here: for Anthropic, CacheRead/CacheWrite are SEPARATE from Input; for
// OpenAI, CachedInput is a SUBSET of Input.
type Usage struct {
	Input       int64
	Output      int64
	CacheRead   int64 // Anthropic cache_read_input_tokens
	CacheWrite  int64 // Anthropic cache_creation_input_tokens
	CachedInput int64 // OpenAI cached_input_tokens (subset of Input)
}

// Table maps a normalized model id to its Rates, with a fallback used when a
// model id is unknown (so a new model never produces a zero/garbage cost
// silently — it bills at the fallback and is flagged by the caller).
type Table struct {
	models   map[string]Rates
	fallback Rates
}

const perMTok = 1_000_000.0

// CostAnthropic computes USD for Anthropic cache semantics: input, output,
// cache-write and cache-read are four independent token pools.
func (t *Table) CostAnthropic(model string, u Usage) float64 {
	r := t.rates(model)
	return (float64(u.Input)*r.InputPerMTok +
		float64(u.Output)*r.OutputPerMTok +
		float64(u.CacheWrite)*r.CacheWritePerMTok +
		float64(u.CacheRead)*r.CacheReadPerMTok) / perMTok
}

// CostOpenAI computes USD for OpenAI cache semantics: CachedInput is a subset of
// Input billed at the cheaper cache-read rate, so the uncached remainder is
// (Input - CachedInput). reasoning_output is already part of Output upstream.
func (t *Table) CostOpenAI(model string, u Usage) float64 {
	r := t.rates(model)
	uncached := u.Input - u.CachedInput
	if uncached < 0 {
		uncached = 0
	}
	return (float64(uncached)*r.InputPerMTok +
		float64(u.CachedInput)*r.CacheReadPerMTok +
		float64(u.Output)*r.OutputPerMTok) / perMTok
}

// Known reports whether the model id resolved to a real entry (not the
// fallback), so callers can warn once on unknown models instead of silently
// mispricing them.
func (t *Table) Known(model string) bool {
	_, ok := t.models[normalizeModel(model)]
	return ok
}

func (t *Table) rates(model string) Rates {
	if r, ok := t.models[normalizeModel(model)]; ok {
		return r
	}
	return t.fallback
}

// normalizeModel collapses a vendor model id to a stable table key. It strips
// date/snapshot suffixes and routing variants so e.g. "claude-opus-4-8",
// "claude-opus-4-7", "claude-opus-4-5-20251101" all map to an "opus" family
// rate, and OpenAI "gpt-5-codex" -> "gpt-5". Pricing within a family is
// identical for the tiers we track, so family-level keys keep the table small
// and forward-compatible with point releases.
func normalizeModel(model string) string {
	m := strings.ToLower(strings.TrimSpace(model))
	switch {
	case strings.Contains(m, "fable"), strings.Contains(m, "mythos"):
		return "fable"
	case strings.Contains(m, "opus"):
		return "opus"
	case strings.Contains(m, "sonnet"):
		return "sonnet"
	case strings.Contains(m, "haiku"):
		return "haiku"
	case strings.Contains(m, "gpt-5"), strings.Contains(m, "gpt5"), strings.Contains(m, "codex"):
		return "gpt-5"
	case strings.HasPrefix(m, "o3"), strings.HasPrefix(m, "o4"):
		return "o-series"
	default:
		return m
	}
}

// DefaultTable returns the built-in price table. Anthropic rates are the current
// per-MTok list prices (Opus $5/$25, Sonnet $3/$15, Haiku $1/$5, Fable $10/$50;
// source: claude-api skill, 2026-06). Anthropic cache tiers follow the standard
// multipliers: 5m cache-write = 1.25× input, cache-read = 0.1× input. OpenAI
// (gpt-5 / codex) rates are public list prices with cached-input at the cached
// rate; revise via CADENCE_TOKEN_PRICING_PATH when they move.
func DefaultTable() *Table {
	return &Table{
		fallback: Rates{InputPerMTok: 5, OutputPerMTok: 25, CacheWritePerMTok: 6.25, CacheReadPerMTok: 0.5},
		models: map[string]Rates{
			// Anthropic — Input, Output, CacheWrite(1.25×in), CacheRead(0.1×in)
			"fable":  {InputPerMTok: 10, OutputPerMTok: 50, CacheWritePerMTok: 12.5, CacheReadPerMTok: 1.0},
			"opus":   {InputPerMTok: 5, OutputPerMTok: 25, CacheWritePerMTok: 6.25, CacheReadPerMTok: 0.5},
			"sonnet": {InputPerMTok: 3, OutputPerMTok: 15, CacheWritePerMTok: 3.75, CacheReadPerMTok: 0.3},
			"haiku":  {InputPerMTok: 1, OutputPerMTok: 5, CacheWritePerMTok: 1.25, CacheReadPerMTok: 0.1},
			// OpenAI — CacheWrite unused (no separate cache-write billing); CacheRead = cached-input rate
			"gpt-5":    {InputPerMTok: 1.25, OutputPerMTok: 10, CacheWritePerMTok: 0, CacheReadPerMTok: 0.125},
			"o-series": {InputPerMTok: 2, OutputPerMTok: 8, CacheWritePerMTok: 0, CacheReadPerMTok: 0.5},
		},
	}
}

// LoadTable reads a JSON price table from path and overlays it on the defaults,
// so an operator can correct or add a single model without restating all rates.
// JSON shape: {"models": {"opus": {"input":5,"output":25,...}, ...},
//
//	"fallback": {"input":5,...}}.
func LoadTable(path string) (*Table, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("token pricing: read %s: %w", path, err)
	}
	var doc struct {
		Models   map[string]Rates `json:"models"`
		Fallback *Rates           `json:"fallback"`
	}
	if err := json.Unmarshal(b, &doc); err != nil {
		return nil, fmt.Errorf("token pricing: parse %s: %w", path, err)
	}
	t := DefaultTable()
	for k, v := range doc.Models {
		t.models[normalizeModel(k)] = v
	}
	if doc.Fallback != nil {
		t.fallback = *doc.Fallback
	}
	return t, nil
}

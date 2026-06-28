package token

import (
	"path/filepath"
	"strings"
	"time"

	"github.com/Aditya1711-tech/cadence/agent/internal/event"
)

// Vendor selects which cache-cost model applies to a Record's counts.
type Vendor string

const (
	VendorAnthropic Vendor = "anthropic" // Claude Code: cache pools separate from input
	VendorOpenAI    Vendor = "openai"    // Codex: cached input is a subset of input
)

// Record is one model turn's usage, extracted from a tool log line. It carries
// ONLY non-sensitive numbers + identifiers — never conversation text (P2-C.2).
type Record struct {
	App     string    // human-readable tool name, e.g. "Claude Code"
	Vendor  Vendor    // selects the cost formula
	Model   string    // meta.model, e.g. "claude-opus-4-8"
	Ts      time.Time // turn timestamp (RFC3339 UTC); token turns are ~instant
	CwdPath string    // raw cwd from the log, reduced to a project name on emit
	Usage   Usage
}

// toEvent converts a Record into an Event Contract event with source:"token".
// The pricing table computes cost_usd (the logs carry none). tokens_in is the
// total input the model processed: for Anthropic that includes the separate
// cache pools; for OpenAI Input already subsumes cached input.
func (r Record) toEvent(memberID string, pricing *Table) (*event.Event, error) {
	e, err := event.New(event.SourceToken, memberID, r.Ts, r.Ts, r.App)
	if err != nil {
		return nil, err
	}

	var tokensIn, cost float64
	switch r.Vendor {
	case VendorOpenAI:
		tokensIn = float64(r.Usage.Input)
		cost = pricing.CostOpenAI(r.Model, r.Usage)
	default:
		tokensIn = float64(r.Usage.Input + r.Usage.CacheRead + r.Usage.CacheWrite)
		cost = pricing.CostAnthropic(r.Model, r.Usage)
	}

	e.Meta[event.MetaModel] = r.Model
	e.Meta[event.MetaTokensIn] = int64(tokensIn)
	e.Meta[event.MetaTokensOut] = r.Usage.Output
	e.Meta[event.MetaCostUSD] = cost
	// Raw sub-counts kept additively so cost stays auditable / re-priceable.
	if r.Usage.CacheRead > 0 {
		e.Meta["cache_read_tokens"] = r.Usage.CacheRead
	}
	if r.Usage.CacheWrite > 0 {
		e.Meta["cache_creation_tokens"] = r.Usage.CacheWrite
	}
	if r.Usage.CachedInput > 0 {
		e.Meta["cached_input_tokens"] = r.Usage.CachedInput
	}
	e.Meta["tool"] = string(r.Vendor)
	e.Meta["priced"] = pricing.Known(r.Model) // false => billed at fallback rate

	if p := projectFromCwd(r.CwdPath); p != "" {
		e.Project = event.Ptr(p)
	}
	return e, nil
}

// projectFromCwd reduces a working-directory path to a best-effort project name
// (its final path segment), so we attribute token spend to a repo without ever
// shipping the full filesystem path as a title. Handles both OS separators since
// logs are read cross-platform (a Windows cwd may be parsed on any host).
func projectFromCwd(cwd string) string {
	cwd = strings.TrimSpace(cwd)
	if cwd == "" {
		return ""
	}
	cwd = strings.ReplaceAll(cwd, "\\", "/")
	cwd = strings.TrimRight(cwd, "/")
	base := filepath.Base(cwd)
	if base == "." || base == "/" || base == "" {
		return ""
	}
	return base
}

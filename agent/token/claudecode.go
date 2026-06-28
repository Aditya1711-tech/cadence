package token

import (
	"bufio"
	"bytes"
	"encoding/json"
	"time"
)

// ClaudeCodeParser turns Claude Code transcript lines into usage Records.
//
// Transcript: ~/.claude/projects/<cwd-slug>/<sessionId>.jsonl, one JSON object
// per line (see docs/P2-C.1). Only "assistant" lines carry usage. The decode
// struct below is a deliberate allow-list: it has NO field for message.content,
// so the JSON decoder skips conversation text entirely — it never lands in a Go
// string we could leak (P2-C.2).
type ClaudeCodeParser struct{}

func (ClaudeCodeParser) Name() string { return "claude_code" }

// ccLine is the narrow projection of a transcript line. Absent keys decode to
// zero values; unknown keys (incl. the sensitive message.content) are ignored.
type ccLine struct {
	Type      string    `json:"type"`
	Timestamp time.Time `json:"timestamp"`
	Cwd       string    `json:"cwd"`
	Message   struct {
		Model string `json:"model"`
		Usage *struct {
			InputTokens              int64 `json:"input_tokens"`
			OutputTokens             int64 `json:"output_tokens"`
			CacheCreationInputTokens int64 `json:"cache_creation_input_tokens"`
			CacheReadInputTokens     int64 `json:"cache_read_input_tokens"`
		} `json:"usage"`
	} `json:"message"`
}

// Parse extracts Records from a chunk of complete JSONL lines. Malformed lines
// and non-usage lines (user/system/tool/summary, or assistant lines without
// usage) are skipped, never errored — a transcript mixes many line types.
func (ClaudeCodeParser) Parse(data []byte) ([]Record, error) {
	var out []Record
	sc := bufio.NewScanner(bytes.NewReader(data))
	sc.Buffer(make([]byte, 0, 64*1024), 16*1024*1024) // transcript lines can be large
	for sc.Scan() {
		line := bytes.TrimSpace(sc.Bytes())
		if len(line) == 0 {
			continue
		}
		var l ccLine
		if err := json.Unmarshal(line, &l); err != nil {
			continue
		}
		if l.Type != "assistant" || l.Message.Usage == nil || l.Message.Model == "" {
			continue
		}
		u := l.Message.Usage
		out = append(out, Record{
			App:     "Claude Code",
			Vendor:  VendorAnthropic,
			Model:   l.Message.Model,
			Ts:      l.Timestamp.UTC(),
			CwdPath: l.Cwd,
			Usage: Usage{
				Input:      u.InputTokens,
				Output:     u.OutputTokens,
				CacheRead:  u.CacheReadInputTokens,
				CacheWrite: u.CacheCreationInputTokens,
			},
		})
	}
	return out, sc.Err()
}

package token

import (
	"bufio"
	"bytes"
	"encoding/json"
	"time"
)

// CodexParser turns Codex CLI rollout lines into usage Records.
//
// Rollout: ~/.codex/sessions/YYYY/MM/DD/rollout-<ts>-<uuid>.jsonl (docs/P2-C.1).
// Unlike Claude Code, the per-turn usage line ("event_msg" -> "token_count")
// does NOT carry the model id or cwd — those appear earlier in the file
// (session_meta / turn_context). So this parser is STATEFUL: it remembers the
// last-seen cwd and model across lines, which also survives incremental tailing
// (one parser instance per file). As with Claude Code, the decode struct omits
// every content/instruction field, so conversation text is never read.
type CodexParser struct {
	cwd          string
	model        string
	defaultModel string
}

// NewCodexParser returns a per-file parser; defaultModel is used until a model
// id is observed in the file (Codex doesn't put it in session_meta).
func NewCodexParser(defaultModel string) *CodexParser {
	if defaultModel == "" {
		defaultModel = "gpt-5-codex"
	}
	return &CodexParser{defaultModel: defaultModel}
}

func (CodexParser) Name() string { return "codex" }

type cxLine struct {
	Type      string    `json:"type"`
	Timestamp time.Time `json:"timestamp"`
	Payload   struct {
		Type  string `json:"type"`  // event_msg subtype, e.g. "token_count"
		Cwd   string `json:"cwd"`   // session_meta / turn_context
		Model string `json:"model"` // turn_context
		Info  *struct {
			LastTokenUsage *struct {
				InputTokens           int64 `json:"input_tokens"`
				CachedInputTokens     int64 `json:"cached_input_tokens"`
				OutputTokens          int64 `json:"output_tokens"`
				ReasoningOutputTokens int64 `json:"reasoning_output_tokens"`
				TotalTokens           int64 `json:"total_tokens"`
			} `json:"last_token_usage"`
		} `json:"info"`
	} `json:"payload"`
}

// Parse consumes a chunk of complete JSONL lines, updating cwd/model state and
// emitting one Record per populated token_count event (last_token_usage = this
// turn's delta; the cumulative total_token_usage is intentionally ignored to
// avoid double counting). Null-info token_count events (e.g. the first, which
// only carries rate_limits) are skipped.
func (p *CodexParser) Parse(data []byte) ([]Record, error) {
	var out []Record
	sc := bufio.NewScanner(bytes.NewReader(data))
	sc.Buffer(make([]byte, 0, 64*1024), 16*1024*1024)
	for sc.Scan() {
		line := bytes.TrimSpace(sc.Bytes())
		if len(line) == 0 {
			continue
		}
		var l cxLine
		if err := json.Unmarshal(line, &l); err != nil {
			continue
		}
		if l.Payload.Cwd != "" {
			p.cwd = l.Payload.Cwd
		}
		if l.Payload.Model != "" {
			p.model = l.Payload.Model
		}
		if l.Type != "event_msg" || l.Payload.Type != "token_count" || l.Payload.Info == nil {
			continue
		}
		u := l.Payload.Info.LastTokenUsage
		if u == nil || (u.InputTokens == 0 && u.OutputTokens == 0) {
			continue
		}
		model := p.model
		if model == "" {
			model = p.defaultModel
		}
		out = append(out, Record{
			App:     "Codex",
			Vendor:  VendorOpenAI,
			Model:   model,
			Ts:      l.Timestamp.UTC(),
			CwdPath: p.cwd,
			Usage: Usage{
				Input:       u.InputTokens,
				Output:      u.OutputTokens,
				CachedInput: u.CachedInputTokens,
			},
		})
	}
	return out, sc.Err()
}

package token

import (
	"encoding/json"
	"github.com/Aditya1711-tech/cadence/agent/internal/event"
	"strings"
	"testing"
)

// sentinel is fake conversation text placed in the content/instruction fields of
// fixture log lines. The P2-C.2 contract is that it must NEVER appear in a
// produced event — the parsers extract an allow-list of numeric fields only.
const sentinel = "SENSITIVE-PROMPT-AND-RESPONSE-TEXT"

func TestClaudeCodeParse_AllowListAndCounts(t *testing.T) {
	// One assistant line with usage (and a content sentinel), one user line, one
	// assistant line without usage — only the first should yield a Record.
	data := []byte(`{"type":"assistant","timestamp":"2026-06-27T09:57:53.731Z","cwd":"c:\\learn\\cadence","sessionId":"s1","message":{"model":"claude-opus-4-8","content":[{"type":"text","text":"` + sentinel + `"}],"usage":{"input_tokens":6126,"output_tokens":830,"cache_creation_input_tokens":3041,"cache_read_input_tokens":20837}}}
{"type":"user","message":{"content":[{"type":"text","text":"` + sentinel + `"}]}}
{"type":"assistant","message":{"model":"claude-opus-4-8"}}
`)
	recs, err := ClaudeCodeParser{}.Parse(data)
	if err != nil {
		t.Fatal(err)
	}
	if len(recs) != 1 {
		t.Fatalf("got %d records, want 1", len(recs))
	}
	r := recs[0]
	if r.Model != "claude-opus-4-8" || r.Vendor != VendorAnthropic {
		t.Fatalf("unexpected model/vendor: %+v", r)
	}
	if r.Usage.Input != 6126 || r.Usage.Output != 830 || r.Usage.CacheWrite != 3041 || r.Usage.CacheRead != 20837 {
		t.Fatalf("unexpected usage: %+v", r.Usage)
	}

	// Sentinel guard: the produced event's full JSON must not contain it.
	e, err := r.toEvent("m1", DefaultTable())
	if err != nil {
		t.Fatal(err)
	}
	b, _ := json.Marshal(e)
	if strings.Contains(string(b), sentinel) {
		t.Fatalf("event leaked conversation text: %s", b)
	}
	if e.Project == nil || *e.Project != "cadence" {
		t.Fatalf("project = %v, want cadence", e.Project)
	}
	if e.Title != nil || e.URL != nil {
		t.Fatal("token events must have null title/url")
	}
	// tokens_in headline = input + cache_read + cache_write.
	if e.Meta[event.MetaTokensIn] != int64(6126+20837+3041) {
		t.Fatalf("tokens_in = %v", e.Meta[event.MetaTokensIn])
	}
}

func TestCodexParse_StatefulModelCwdAndNullInfo(t *testing.T) {
	data := []byte(`{"type":"session_meta","timestamp":"2026-02-03T18:17:41.734Z","payload":{"cwd":"c:\\vyttah\\proj","model_provider":"openai","base_instructions":"` + sentinel + `"}}
{"type":"turn_context","payload":{"model":"gpt-5-codex","cwd":"c:\\vyttah\\proj"}}
{"type":"event_msg","timestamp":"2026-02-03T18:17:47.820Z","payload":{"type":"token_count","info":null}}
{"type":"event_msg","timestamp":"2026-02-03T18:17:50.000Z","payload":{"type":"token_count","info":{"last_token_usage":{"input_tokens":10200,"cached_input_tokens":8448,"output_tokens":58,"reasoning_output_tokens":0,"total_tokens":10258},"total_token_usage":{"input_tokens":99999}}}}
`)
	p := NewCodexParser("")
	recs, err := p.Parse(data)
	if err != nil {
		t.Fatal(err)
	}
	if len(recs) != 1 { // null-info event skipped; cumulative not double-counted
		t.Fatalf("got %d records, want 1", len(recs))
	}
	r := recs[0]
	if r.Model != "gpt-5-codex" || r.Vendor != VendorOpenAI {
		t.Fatalf("unexpected model/vendor: %+v", r)
	}
	if r.Usage.Input != 10200 || r.Usage.CachedInput != 8448 || r.Usage.Output != 58 {
		t.Fatalf("unexpected usage: %+v", r.Usage)
	}
	if r.CwdPath == "" {
		t.Fatal("cwd not carried from session_meta/turn_context")
	}

	e, err := r.toEvent("m1", DefaultTable())
	if err != nil {
		t.Fatal(err)
	}
	b, _ := json.Marshal(e)
	if strings.Contains(string(b), sentinel) {
		t.Fatalf("event leaked instruction text: %s", b)
	}
	// OpenAI headline tokens_in = input (cached is a subset, already counted).
	if e.Meta[event.MetaTokensIn] != int64(10200) {
		t.Fatalf("tokens_in = %v, want 10200", e.Meta[event.MetaTokensIn])
	}
}

func TestCodexParse_DefaultModelWhenAbsent(t *testing.T) {
	data := []byte(`{"type":"event_msg","timestamp":"2026-02-03T18:17:50.000Z","payload":{"type":"token_count","info":{"last_token_usage":{"input_tokens":10,"output_tokens":2}}}}` + "\n")
	p := NewCodexParser("gpt-5")
	recs, err := p.Parse(data)
	if err != nil {
		t.Fatal(err)
	}
	if len(recs) != 1 || recs[0].Model != "gpt-5" {
		t.Fatalf("expected default model gpt-5, got %+v", recs)
	}
}

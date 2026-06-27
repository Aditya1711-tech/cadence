package event

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"
)

// contractKeyOrder is the frozen wire order of the Event Contract (§5). Changing
// it is a coordination event.
var contractKeyOrder = []string{
	"event_id", "schema_ver", "source", "member_id", "ts_start", "ts_end",
	"duration_ms", "app", "title", "url", "project", "category", "is_idle", "meta",
}

func goldenEvent(t *testing.T) Event {
	t.Helper()
	ts := mustTime(t, "2025-06-01T09:14:02Z")
	te := mustTime(t, "2025-06-01T09:18:45Z")
	e := Event{
		EventID:   "11111111-1111-4111-8111-111111111111",
		SchemaVer: SchemaVersion,
		Source:    SourceVSCode,
		MemberID:  "22222222-2222-4222-8222-222222222222",
		TsStart:   ts,
		TsEnd:     te,
		App:       "Visual Studio Code",
		Title:     Ptr("auth.ts — cadence-api"),
		URL:       nil,
		Project:   Ptr("cadence-api"),
		Category:  Ptr(CategoryDeepWork),
		IsIdle:    false,
		Meta:      Meta{MetaLang: "typescript"},
	}
	e.ComputeDuration()
	return e
}

func mustTime(t *testing.T, s string) time.Time {
	t.Helper()
	tm, err := time.Parse(time.RFC3339, s)
	if err != nil {
		t.Fatalf("parse time %q: %v", s, err)
	}
	return tm
}

func toMap(t *testing.T, b []byte) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal(b, &m); err != nil {
		t.Fatalf("unmarshal to map: %v\n%s", err, b)
	}
	return m
}

// TestMatchesGolden checks our marshalled event is semantically identical to the
// canonical golden sample that the Wave-1 streams consume.
func TestMatchesGolden(t *testing.T) {
	e := goldenEvent(t)
	got, err := json.Marshal(e)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	goldenBytes, err := os.ReadFile(filepath.Join("testdata", "golden_event.json"))
	if err != nil {
		t.Fatalf("read golden: %v", err)
	}
	if !reflect.DeepEqual(toMap(t, got), toMap(t, goldenBytes)) {
		t.Fatalf("event does not match golden:\n got=%s\nwant=%s", got, goldenBytes)
	}
}

// TestWireKeyOrderFrozen locks the field order on the wire.
func TestWireKeyOrderFrozen(t *testing.T) {
	got, err := json.Marshal(goldenEvent(t))
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	s := string(got)
	prev := -1
	for _, k := range contractKeyOrder {
		idx := strings.Index(s, `"`+k+`":`)
		if idx < 0 {
			t.Fatalf("contract key %q missing from output: %s", k, s)
		}
		if idx <= prev {
			t.Fatalf("contract key %q out of order in: %s", k, s)
		}
		prev = idx
	}
}

// TestAllKeysAlwaysPresent verifies the "never omit a key, send null instead"
// rule: even a minimally-filled event emits all 14 keys, with nullables null.
func TestAllKeysAlwaysPresent(t *testing.T) {
	e, err := New(SourceOS, "member-1", mustTime(t, "2025-06-01T00:00:00Z"),
		mustTime(t, "2025-06-01T00:00:01Z"), "Finder")
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	b, err := json.Marshal(e)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	m := toMap(t, b)
	for _, k := range contractKeyOrder {
		if _, ok := m[k]; !ok {
			t.Errorf("key %q omitted; contract requires it present (null if empty)", k)
		}
	}
	for _, k := range []string{"title", "url", "project", "category"} {
		if m[k] != nil {
			t.Errorf("expected %q to be null, got %v", k, m[k])
		}
	}
	if !strings.Contains(string(b), `"url":null`) {
		t.Errorf("nullable url should render as null: %s", b)
	}
}

// TestMetaNeverNull ensures meta is always an object, even when nil.
func TestMetaNeverNull(t *testing.T) {
	e := Event{Meta: nil}
	b, err := json.Marshal(e)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	if !strings.Contains(string(b), `"meta":{}`) {
		t.Errorf("nil meta must marshal as {}; got %s", b)
	}
}

// TestMetaUnknownKeysPreserved verifies meta is additive: unknown keys survive a
// round-trip and are never rejected.
func TestMetaUnknownKeysPreserved(t *testing.T) {
	raw := `{
      "event_id":"e1","schema_ver":1,"source":"token","member_id":"m1",
      "ts_start":"2025-06-01T00:00:00Z","ts_end":"2025-06-01T00:00:00Z",
      "duration_ms":0,"app":"Claude Code","title":null,"url":null,
      "project":null,"category":null,"is_idle":false,
      "meta":{"model":"claude-sonnet-4","tokens_in":12000,"future_key":"keep-me"}
    }`
	var e Event
	if err := json.Unmarshal([]byte(raw), &e); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if e.Meta["future_key"] != "keep-me" {
		t.Fatalf("unknown meta key dropped: %#v", e.Meta)
	}
	out, err := json.Marshal(e)
	if err != nil {
		t.Fatalf("re-marshal: %v", err)
	}
	if !strings.Contains(string(out), `"future_key":"keep-me"`) {
		t.Errorf("unknown meta key not preserved on round-trip: %s", out)
	}
}

func TestComputeDurationAndNew(t *testing.T) {
	e, err := New(SourceVSCode, "m1", mustTime(t, "2025-06-01T09:14:02Z"),
		mustTime(t, "2025-06-01T09:18:45Z"), "Visual Studio Code")
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	if e.DurationMs != 283000 {
		t.Errorf("duration_ms = %d, want 283000", e.DurationMs)
	}
	if e.SchemaVer != SchemaVersion {
		t.Errorf("schema_ver = %d, want %d", e.SchemaVer, SchemaVersion)
	}
	if e.Meta == nil {
		t.Errorf("New must initialize meta to a non-nil map")
	}
	if e.TsStart.Location() != time.UTC {
		t.Errorf("New must normalize ts_start to UTC")
	}
}

func TestValidate(t *testing.T) {
	base := func() Event { return goldenEvent(t) }

	valid := base()
	if err := valid.Validate(); err != nil {
		t.Fatalf("golden event should validate, got: %v", err)
	}

	cases := []struct {
		name   string
		mutate func(*Event)
	}{
		{"bad schema_ver", func(e *Event) { e.SchemaVer = 2 }},
		{"empty event_id", func(e *Event) { e.EventID = "" }},
		{"bad source", func(e *Event) { e.Source = Source("slack") }},
		{"empty member_id", func(e *Event) { e.MemberID = "" }},
		{"empty app", func(e *Event) { e.App = "" }},
		{"end before start", func(e *Event) { e.TsEnd = e.TsStart.Add(-time.Second) }},
		{"duration mismatch", func(e *Event) { e.DurationMs = 1 }},
		{"bad category", func(e *Event) { e.Category = Ptr(Category("napping")) }},
		{"zero timestamps", func(e *Event) { e.TsStart = time.Time{}; e.TsEnd = time.Time{} }},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			e := base()
			c.mutate(&e)
			if err := e.Validate(); err == nil {
				t.Errorf("expected validation error for %q, got nil", c.name)
			}
		})
	}
}

func TestSourceAndCategoryValidity(t *testing.T) {
	for _, s := range AllSources() {
		if !s.IsValid() {
			t.Errorf("AllSources contains invalid source %q", s)
		}
	}
	if Source("nope").IsValid() {
		t.Errorf("unexpected valid source")
	}
	for _, c := range AllCategories() {
		if !c.IsValid() {
			t.Errorf("AllCategories contains invalid category %q", c)
		}
	}
	if Category("nope").IsValid() {
		t.Errorf("unexpected valid category")
	}
	if len(AllCategories()) != 8 {
		t.Errorf("expected 8 categories, got %d", len(AllCategories()))
	}
}

func TestNewID(t *testing.T) {
	seen := map[string]bool{}
	for i := 0; i < 100; i++ {
		id, err := NewID()
		if err != nil {
			t.Fatalf("NewID: %v", err)
		}
		if len(id) != 36 {
			t.Fatalf("uuid wrong length %d: %q", len(id), id)
		}
		if id[14] != '4' {
			t.Errorf("uuid version nibble not 4: %q", id)
		}
		if v := id[19]; v != '8' && v != '9' && v != 'a' && v != 'b' {
			t.Errorf("uuid variant nibble unexpected %c: %q", v, id)
		}
		if seen[id] {
			t.Fatalf("duplicate uuid generated: %q", id)
		}
		seen[id] = true
	}
}

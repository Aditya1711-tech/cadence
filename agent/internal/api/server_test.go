package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/Aditya1711-tech/cadence/agent/internal/event"
)

// fakeStore is an in-memory Store for handler tests (no sqlite/crypto needed).
type fakeStore struct {
	events   []event.Event
	queryErr error
	countErr error
}

func (f *fakeStore) Append(e *event.Event) error {
	if err := e.Validate(); err != nil {
		return err
	}
	for _, ex := range f.events { // idempotent on event_id
		if ex.EventID == e.EventID {
			return nil
		}
	}
	f.events = append(f.events, *e)
	return nil
}

func (f *fakeStore) Query(from, to time.Time) ([]event.Event, error) {
	if f.queryErr != nil {
		return nil, f.queryErr
	}
	var out []event.Event
	for _, e := range f.events {
		if !e.TsStart.Before(from) && e.TsStart.Before(to) {
			out = append(out, e)
		}
	}
	return out, nil
}

func (f *fakeStore) Count() (int, error) {
	if f.countErr != nil {
		return 0, f.countErr
	}
	return len(f.events), nil
}

func newReq(t *testing.T, method, target, body string) *http.Request {
	t.Helper()
	req := httptest.NewRequest(method, target, strings.NewReader(body))
	req.RemoteAddr = "127.0.0.1:54321" // loopback so the guard passes
	return req
}

func do(t *testing.T, h http.Handler, req *http.Request) *httptest.ResponseRecorder {
	t.Helper()
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, req)
	return rr
}

func sampleJSON(t *testing.T, id string, start time.Time) string {
	t.Helper()
	e, err := event.New(event.SourceVSCode, "m1", start, start.Add(time.Minute), "Visual Studio Code")
	if err != nil {
		t.Fatalf("new: %v", err)
	}
	e.EventID = id
	e.Category = event.Ptr(event.CategoryDeepWork)
	b, err := json.Marshal(e)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	return string(b)
}

func TestPostSingleEvent(t *testing.T) {
	fs := &fakeStore{}
	h := New(fs, nil).Handler()
	start := time.Date(2025, 6, 1, 9, 0, 0, 0, time.UTC)
	rr := do(t, h, newReq(t, "POST", "/events", sampleJSON(t, "id-1", start)))
	if rr.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body=%s", rr.Code, rr.Body)
	}
	var res postEventsResult
	if err := json.Unmarshal(rr.Body.Bytes(), &res); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if res.Accepted != 1 || res.Rejected != 0 {
		t.Fatalf("accepted/rejected = %d/%d, want 1/0", res.Accepted, res.Rejected)
	}
	if len(fs.events) != 1 {
		t.Fatalf("store has %d events, want 1", len(fs.events))
	}
}

func TestPostArrayAndIdempotency(t *testing.T) {
	fs := &fakeStore{}
	h := New(fs, nil).Handler()
	start := time.Date(2025, 6, 1, 9, 0, 0, 0, time.UTC)
	body := "[" + sampleJSON(t, "id-1", start) + "," + sampleJSON(t, "id-1", start) + "," + sampleJSON(t, "id-2", start) + "]"
	rr := do(t, h, newReq(t, "POST", "/events", body))
	if rr.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body=%s", rr.Code, rr.Body)
	}
	if len(fs.events) != 2 {
		t.Fatalf("store has %d events, want 2 (idempotent on event_id)", len(fs.events))
	}
}

func TestPostRejectsInvalidEventButKeepsValid(t *testing.T) {
	fs := &fakeStore{}
	h := New(fs, nil).Handler()
	start := time.Date(2025, 6, 1, 9, 0, 0, 0, time.UTC)
	good := sampleJSON(t, "good", start)
	bad := `{"event_id":"bad","schema_ver":1,"source":"not-a-source","member_id":"m1","ts_start":"2025-06-01T09:00:00Z","ts_end":"2025-06-01T09:00:00Z","duration_ms":0,"app":"x","title":null,"url":null,"project":null,"category":null,"is_idle":false,"meta":{}}`
	rr := do(t, h, newReq(t, "POST", "/events", "["+good+","+bad+"]"))
	if rr.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rr.Code)
	}
	var res postEventsResult
	json.Unmarshal(rr.Body.Bytes(), &res)
	if res.Accepted != 1 || res.Rejected != 1 {
		t.Fatalf("accepted/rejected = %d/%d, want 1/1", res.Accepted, res.Rejected)
	}
	if len(res.Errors) != 1 {
		t.Fatalf("expected 1 error message, got %v", res.Errors)
	}
}

func TestPostMalformedJSON(t *testing.T) {
	h := New(&fakeStore{}, nil).Handler()
	rr := do(t, h, newReq(t, "POST", "/events", "{not json"))
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", rr.Code)
	}
	if ct := rr.Header().Get("Content-Type"); ct != "application/problem+json" {
		t.Errorf("content-type = %q, want problem+json", ct)
	}
}

func TestPostEmptyBody(t *testing.T) {
	h := New(&fakeStore{}, nil).Handler()
	rr := do(t, h, newReq(t, "POST", "/events", ""))
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", rr.Code)
	}
}

func TestTimelineReturnsEventsInRange(t *testing.T) {
	fs := &fakeStore{}
	h := New(fs, nil).Handler()
	base := time.Date(2025, 6, 1, 0, 0, 0, 0, time.UTC)
	for i, off := range []time.Duration{0, time.Hour, 3 * time.Hour} {
		e, _ := event.New(event.SourceOS, "m1", base.Add(off), base.Add(off+time.Minute), "Finder")
		e.EventID = "e" + strings.Repeat("x", i+1)
		fs.events = append(fs.events, *e)
	}
	rr := do(t, h, newReq(t, "GET",
		"/timeline?from=2025-06-01T00:00:00Z&to=2025-06-01T02:00:00Z", ""))
	if rr.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body=%s", rr.Code, rr.Body)
	}
	var got []event.Event
	if err := json.Unmarshal(rr.Body.Bytes(), &got); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(got) != 2 {
		t.Fatalf("got %d events in window, want 2", len(got))
	}
}

func TestTimelineEmptyIsArrayNotNull(t *testing.T) {
	h := New(&fakeStore{}, nil).Handler()
	rr := do(t, h, newReq(t, "GET", "/timeline", ""))
	if rr.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rr.Code)
	}
	if body := strings.TrimSpace(rr.Body.String()); body != "[]" {
		t.Fatalf("empty timeline body = %q, want []", body)
	}
}

func TestTimelineBadRange(t *testing.T) {
	h := New(&fakeStore{}, nil).Handler()
	rr := do(t, h, newReq(t, "GET", "/timeline?from=2025-06-02T00:00:00Z&to=2025-06-01T00:00:00Z", ""))
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", rr.Code)
	}
	rr2 := do(t, h, newReq(t, "GET", "/timeline?from=not-a-time", ""))
	if rr2.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", rr2.Code)
	}
}

func TestHealthz(t *testing.T) {
	fs := &fakeStore{}
	e, _ := event.New(event.SourceOS, "m1", time.Now().UTC(), time.Now().UTC(), "Finder")
	fs.events = append(fs.events, *e)
	h := New(fs, nil).Handler()
	rr := do(t, h, newReq(t, "GET", "/healthz", ""))
	if rr.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rr.Code)
	}
	var body map[string]any
	json.Unmarshal(rr.Body.Bytes(), &body)
	if body["status"] != "ok" {
		t.Errorf("status field = %v", body["status"])
	}
	if body["events"].(float64) != 1 {
		t.Errorf("events = %v, want 1", body["events"])
	}
}

func TestNonLoopbackForbidden(t *testing.T) {
	h := New(&fakeStore{}, nil).Handler()
	req := httptest.NewRequest("GET", "/healthz", nil)
	req.RemoteAddr = "203.0.113.7:9999" // public IP
	rr := do(t, h, req)
	if rr.Code != http.StatusForbidden {
		t.Fatalf("status = %d, want 403 for non-loopback", rr.Code)
	}
}

func TestMethodNotAllowed(t *testing.T) {
	h := New(&fakeStore{}, nil).Handler()
	rr := do(t, h, newReq(t, "DELETE", "/events", ""))
	if rr.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status = %d, want 405", rr.Code)
	}
}

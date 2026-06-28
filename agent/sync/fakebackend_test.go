package cloudsync

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"github.com/Aditya1711-tech/cadence/agent/internal/event"
)

// fakeBackend is a configurable stand-in for the P2-A backend used by client and
// syncer tests. Behavior is toggled through its exported fields under mu.
type fakeBackend struct {
	mu     sync.Mutex
	server *httptest.Server

	// ingest behavior
	acceptAccess string // if non-empty, ingest 401s unless the bearer matches
	failIngest   bool   // if true, ingest returns 503 (transient)
	received     map[string]struct{}
	ingestCalls  int

	// refresh behavior
	refreshStatus int        // if non-zero, refresh returns this status
	refreshTo     *TokenPair // success payload
	refreshCalls  int

	// enroll behavior
	enrollStatus int
	enrollResp   *EnrollResponse
	enrollCalls  int
}

func newFakeBackend(t *testing.T) *fakeBackend {
	t.Helper()
	b := &fakeBackend{received: make(map[string]struct{})}
	b.server = httptest.NewServer(http.HandlerFunc(b.handle))
	t.Cleanup(b.server.Close)
	return b
}

func (b *fakeBackend) base() string { return b.server.URL }

func (b *fakeBackend) handle(w http.ResponseWriter, r *http.Request) {
	b.mu.Lock()
	defer b.mu.Unlock()
	switch r.URL.Path {
	case pathEnroll:
		b.enrollCalls++
		if b.enrollStatus != 0 {
			writeProblemJSON(w, b.enrollStatus, "enroll failed")
			return
		}
		resp := b.enrollResp
		if resp == nil {
			resp = &EnrollResponse{MemberID: "m-1", AccessToken: "a1", RefreshToken: "r1", TokenType: "Bearer", ExpiresInSeconds: 3600}
		}
		writeJSONResp(w, 200, resp)
	case pathRefresh:
		b.refreshCalls++
		if b.refreshStatus != 0 {
			writeProblemJSON(w, b.refreshStatus, "refresh rejected")
			return
		}
		tp := b.refreshTo
		if tp == nil {
			tp = &TokenPair{AccessToken: "a2", RefreshToken: "r2", TokenType: "Bearer", ExpiresInSeconds: 3600}
		}
		writeJSONResp(w, 200, tp)
	case pathIngest:
		b.ingestCalls++
		if b.failIngest {
			writeProblemJSON(w, 503, "service unavailable")
			return
		}
		if b.acceptAccess != "" && r.Header.Get("Authorization") != "Bearer "+b.acceptAccess {
			writeProblemJSON(w, 401, "unauthorized")
			return
		}
		var evs []event.Event
		if err := json.NewDecoder(r.Body).Decode(&evs); err != nil {
			writeProblemJSON(w, 400, "bad json")
			return
		}
		dup := 0
		for _, e := range evs {
			if _, ok := b.received[e.EventID]; ok {
				dup++
				continue
			}
			b.received[e.EventID] = struct{}{}
		}
		writeJSONResp(w, 200, IngestResult{Received: len(evs), Stored: len(evs) - dup, Duplicates: dup})
	default:
		http.NotFound(w, r)
	}
}

func (b *fakeBackend) set(fn func(*fakeBackend)) {
	b.mu.Lock()
	defer b.mu.Unlock()
	fn(b)
}

func (b *fakeBackend) receivedCount() int {
	b.mu.Lock()
	defer b.mu.Unlock()
	return len(b.received)
}

func (b *fakeBackend) calls() (ingest, refresh, enroll int) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.ingestCalls, b.refreshCalls, b.enrollCalls
}

func writeJSONResp(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeProblemJSON(w http.ResponseWriter, status int, detail string) {
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{
		"type": "about:blank", "title": strings.ToLower(http.StatusText(status)), "status": status, "detail": detail,
	})
}

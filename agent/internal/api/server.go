// Package api is the agent's local IPC surface: a small HTTP server bound to
// 127.0.0.1 that collectors (P1-B vscode, P1-C chrome) POST events to and the
// dashboard (P1-D) reads timelines from. There is no auth in Phase 1 — the
// server refuses any non-loopback peer, and everything stays on the device.
//
// Routes:
//
//	POST /events             accept one event or a JSON array (max 1000),
//	                         idempotent on event_id; body is the §5 contract.
//	GET  /timeline?from&to   events with ts_start in [from,to) (RFC3339 UTC);
//	                         defaults to the last 24h. Returns a JSON array.
//	GET  /healthz            liveness + stored event count + schema_ver.
package api

import (
	"bytes"
	"encoding/json"
	"io"
	"log/slog"
	"net"
	"net/http"
	"strconv"
	"time"

	"github.com/Aditya1711-tech/cadence/agent/internal/event"
)

const (
	// MaxBatch is the most events accepted in one POST /events call (§6).
	MaxBatch = 1000
	// maxBody caps the request body to bound memory per call.
	maxBody = 8 << 20 // 8 MiB
	// defaultRange is the timeline window when from/to are omitted.
	defaultRange = 24 * time.Hour
)

// Store is the persistence surface the API needs. The concrete
// store.Store satisfies it; tests inject a fake.
type Store interface {
	Append(*event.Event) error
	Query(from, to time.Time) ([]event.Event, error)
	Count() (int, error)
}

// Server serves the local API over a Store.
type Server struct {
	store Store
	log   *slog.Logger
}

// New builds a Server. If logger is nil, a discarding logger is used.
func New(st Store, logger *slog.Logger) *Server {
	if logger == nil {
		logger = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	return &Server{store: st, log: logger}
}

// Handler returns the routed http.Handler, wrapped so only loopback peers are
// served.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /events", s.handlePostEvents)
	mux.HandleFunc("GET /timeline", s.handleTimeline)
	mux.HandleFunc("GET /healthz", s.handleHealth)
	return loopbackOnly(mux)
}

// postEventsResult is the body returned by POST /events.
type postEventsResult struct {
	Accepted int      `json:"accepted"`
	Rejected int      `json:"rejected"`
	Errors   []string `json:"errors"`
}

func (s *Server) handlePostEvents(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, maxBody)
	raw, err := io.ReadAll(r.Body)
	if err != nil {
		writeProblem(w, http.StatusRequestEntityTooLarge, "request body too large",
			"body exceeds the local ingest limit")
		return
	}
	trimmed := bytes.TrimSpace(raw)
	if len(trimmed) == 0 {
		writeProblem(w, http.StatusBadRequest, "empty body", "expected a JSON event or array of events")
		return
	}

	var events []event.Event
	if trimmed[0] == '[' {
		if err := json.Unmarshal(trimmed, &events); err != nil {
			writeProblem(w, http.StatusBadRequest, "invalid JSON array", err.Error())
			return
		}
	} else {
		var one event.Event
		if err := json.Unmarshal(trimmed, &one); err != nil {
			writeProblem(w, http.StatusBadRequest, "invalid JSON event", err.Error())
			return
		}
		events = []event.Event{one}
	}

	if len(events) > MaxBatch {
		writeProblem(w, http.StatusRequestEntityTooLarge, "batch too large",
			"a single POST accepts at most 1000 events")
		return
	}

	res := postEventsResult{Errors: []string{}}
	for i := range events {
		if err := s.store.Append(&events[i]); err != nil {
			res.Rejected++
			res.Errors = append(res.Errors, "event "+strconv.Itoa(i)+": "+err.Error())
			s.log.Warn("rejected event", "index", i, "err", err)
			continue
		}
		res.Accepted++
	}
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) handleTimeline(w http.ResponseWriter, r *http.Request) {
	from, to, err := parseRange(r)
	if err != nil {
		writeProblem(w, http.StatusBadRequest, "invalid range", err.Error())
		return
	}
	events, err := s.store.Query(from, to)
	if err != nil {
		s.log.Error("timeline query failed", "err", err)
		writeProblem(w, http.StatusInternalServerError, "query failed", "could not read the local store")
		return
	}
	if events == nil {
		events = []event.Event{} // emit [] not null
	}
	writeJSON(w, http.StatusOK, events)
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	n, err := s.store.Count()
	if err != nil {
		writeProblem(w, http.StatusInternalServerError, "unhealthy", "could not read the local store")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":     "ok",
		"events":     n,
		"schema_ver": event.SchemaVersion,
	})
}

// parseRange reads from/to query params as RFC3339 UTC, defaulting to the last
// defaultRange ending now. It rejects to < from.
func parseRange(r *http.Request) (from, to time.Time, err error) {
	now := time.Now().UTC()
	to = now
	from = now.Add(-defaultRange)
	q := r.URL.Query()
	if v := q.Get("from"); v != "" {
		if from, err = time.Parse(time.RFC3339, v); err != nil {
			return time.Time{}, time.Time{}, errInvalid("from must be RFC3339: " + err.Error())
		}
	}
	if v := q.Get("to"); v != "" {
		if to, err = time.Parse(time.RFC3339, v); err != nil {
			return time.Time{}, time.Time{}, errInvalid("to must be RFC3339: " + err.Error())
		}
	}
	if to.Before(from) {
		return time.Time{}, time.Time{}, errInvalid("to is before from")
	}
	return from.UTC(), to.UTC(), nil
}

// loopbackOnly rejects any request whose peer is not a loopback address. This
// is the Phase-1 stand-in for auth: the surface is local-only.
func loopbackOnly(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		host, _, err := net.SplitHostPort(r.RemoteAddr)
		if err != nil {
			host = r.RemoteAddr
		}
		if ip := net.ParseIP(host); ip == nil || !ip.IsLoopback() {
			writeProblem(w, http.StatusForbidden, "forbidden", "the agent serves loopback clients only")
			return
		}
		next.ServeHTTP(w, r)
	})
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

// writeProblem emits an RFC 7807 problem+json response (§6).
func writeProblem(w http.ResponseWriter, status int, title, detail string) {
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{
		"type":   "about:blank",
		"title":  title,
		"status": status,
		"detail": detail,
	})
}

type rangeError struct{ msg string }

func (e rangeError) Error() string { return e.msg }
func errInvalid(msg string) error  { return rangeError{msg} }

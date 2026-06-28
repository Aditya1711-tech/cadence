// Package cloudsync ships locally-stored events to the Cadence cloud ingest
// endpoint (P2-B). It is the daemon's client-side glue: a background loop that
// pulls un-synced events from the local store, POSTs them in idempotent
// batches, and tracks delivery — plus device enrollment and token refresh.
//
// Ownership (docs/00-SYSTEM-KNOWLEDGE.md §9): this stream owns only
// /agent/sync/. It must not edit the P1-A-owned local store. Sync-delivery
// state therefore lives in a SELF-CONTAINED sidecar database this package owns
// (see State), not in the events table. This is the operator-approved Option A
// (docs/PROGRESS.md coordination block); the cleaner long-term form is a
// store-level synced_at column when P1-A next touches the store (§7.1).
package cloudsync

import (
	"database/sql"
	"fmt"
	"time"

	"github.com/Aditya1711-tech/cadence/agent/internal/event"

	_ "modernc.org/sqlite" // pure-Go SQLite driver, registered as "sqlite"
)

// stateSchema is the sidecar's schema. `synced` is the durable dedupe record:
// one row per event we have confirmed the cloud accepted (2xx). `meta` holds
// the scan watermark. We keep ts_start_ms alongside each synced id so the table
// can be pruned once the watermark has moved safely past an event.
const stateSchema = `
CREATE TABLE IF NOT EXISTS synced (
    event_id     TEXT    PRIMARY KEY,
    ts_start_ms  INTEGER NOT NULL,
    synced_at_ms INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_synced_ts_start ON synced (ts_start_ms);
CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
`

// metaWatermark is the meta key holding the low-water-mark: the ts_start (epoch
// ms) below which every event is known-synced. The scan never starts earlier
// than this, which bounds the per-cycle Query window. It never advances past an
// un-synced event, which is what makes recovery-from-offline durable.
const metaWatermark = "scan_from_ms"

// State is the self-contained sync-delivery store (sidecar SQLite DB). It is the
// single owner of "what have we already shipped" — the local event store knows
// nothing about sync.
type State struct {
	db *sql.DB
}

// OpenState opens (creating if needed) the sidecar DB at dbPath.
func OpenState(dbPath string) (*State, error) {
	db, err := sql.Open("sqlite", dbPath)
	if err != nil {
		return nil, fmt.Errorf("cloudsync: open state %s: %w", dbPath, err)
	}
	db.SetMaxOpenConns(1) // single writer, like the main store
	for _, pragma := range []string{
		"PRAGMA journal_mode=WAL",
		"PRAGMA busy_timeout=5000",
		"PRAGMA synchronous=NORMAL",
	} {
		if _, err := db.Exec(pragma); err != nil {
			db.Close()
			return nil, fmt.Errorf("cloudsync: state %s: %w", pragma, err)
		}
	}
	if _, err := db.Exec(stateSchema); err != nil {
		db.Close()
		return nil, fmt.Errorf("cloudsync: apply state schema: %w", err)
	}
	return &State{db: db}, nil
}

// Close releases the sidecar handle.
func (s *State) Close() error { return s.db.Close() }

// Watermark returns the persisted scan low-water-mark as a time. Zero (epoch) on
// first run, meaning "scan from the beginning."
func (s *State) Watermark() (time.Time, error) {
	var v sql.NullString
	err := s.db.QueryRow(`SELECT value FROM meta WHERE key = ?`, metaWatermark).Scan(&v)
	if err == sql.ErrNoRows {
		return time.UnixMilli(0).UTC(), nil
	}
	if err != nil {
		return time.Time{}, fmt.Errorf("cloudsync: read watermark: %w", err)
	}
	ms, perr := parseInt64(v.String)
	if perr != nil {
		return time.Time{}, fmt.Errorf("cloudsync: corrupt watermark %q: %w", v.String, perr)
	}
	return time.UnixMilli(ms).UTC(), nil
}

// SetWatermark persists the scan low-water-mark. Callers must never set it later
// than the oldest still-un-synced event, or that event would never be scanned.
func (s *State) SetWatermark(t time.Time) error {
	_, err := s.db.Exec(
		`INSERT INTO meta (key, value) VALUES (?, ?)
		 ON CONFLICT(key) DO UPDATE SET value = excluded.value`,
		metaWatermark, formatInt64(t.UnixMilli()),
	)
	if err != nil {
		return fmt.Errorf("cloudsync: set watermark: %w", err)
	}
	return nil
}

// Unsynced returns, from the given candidate events (typically a store.Query
// window), those not yet confirmed-synced — preserving input order. It is a
// pure read; nothing is marked.
func (s *State) Unsynced(candidates []event.Event) ([]event.Event, error) {
	if len(candidates) == 0 {
		return nil, nil
	}
	synced, err := s.syncedSet()
	if err != nil {
		return nil, err
	}
	out := make([]event.Event, 0, len(candidates))
	for _, e := range candidates {
		if _, ok := synced[e.EventID]; !ok {
			out = append(out, e)
		}
	}
	return out, nil
}

// MarkSynced records that the given events were accepted by the cloud (2xx). It
// is idempotent: re-marking an already-synced id is a no-op.
func (s *State) MarkSynced(events []event.Event) error {
	if len(events) == 0 {
		return nil
	}
	tx, err := s.db.Begin()
	if err != nil {
		return fmt.Errorf("cloudsync: begin mark: %w", err)
	}
	defer tx.Rollback() //nolint:errcheck // no-op after a successful Commit
	now := time.Now().UnixMilli()
	stmt, err := tx.Prepare(
		`INSERT INTO synced (event_id, ts_start_ms, synced_at_ms) VALUES (?, ?, ?)
		 ON CONFLICT(event_id) DO NOTHING`)
	if err != nil {
		return fmt.Errorf("cloudsync: prepare mark: %w", err)
	}
	defer stmt.Close()
	for i := range events {
		if _, err := stmt.Exec(events[i].EventID, events[i].TsStart.UnixMilli(), now); err != nil {
			return fmt.Errorf("cloudsync: mark %s: %w", events[i].EventID, err)
		}
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("cloudsync: commit mark: %w", err)
	}
	return nil
}

// Prune deletes synced rows for events whose ts_start is at or before cutoff.
// Safe to call with cutoff = watermark − margin: once the watermark has moved
// past an event it is never re-queried, so its dedupe row is dead weight.
func (s *State) Prune(cutoff time.Time) (int64, error) {
	res, err := s.db.Exec(`DELETE FROM synced WHERE ts_start_ms <= ?`, cutoff.UnixMilli())
	if err != nil {
		return 0, fmt.Errorf("cloudsync: prune: %w", err)
	}
	n, _ := res.RowsAffected()
	return n, nil
}

// Count returns the number of synced rows currently tracked. Used by `status`.
func (s *State) Count() (int, error) {
	var n int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM synced`).Scan(&n); err != nil {
		return 0, fmt.Errorf("cloudsync: count synced: %w", err)
	}
	return n, nil
}

func (s *State) syncedSet() (map[string]struct{}, error) {
	rows, err := s.db.Query(`SELECT event_id FROM synced`)
	if err != nil {
		return nil, fmt.Errorf("cloudsync: read synced set: %w", err)
	}
	defer rows.Close()
	set := make(map[string]struct{})
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, fmt.Errorf("cloudsync: scan synced id: %w", err)
		}
		set[id] = struct{}{}
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("cloudsync: iterate synced: %w", err)
	}
	return set, nil
}

func parseInt64(s string) (int64, error) {
	var v int64
	_, err := fmt.Sscan(s, &v)
	return v, err
}

func formatInt64(v int64) string { return fmt.Sprintf("%d", v) }

// Package store is the agent's local event store: a pure-Go SQLite database
// (modernc.org/sqlite, no CGO) with app-level column encryption.
//
// Layout decision (see docs/00-SYSTEM-KNOWLEDGE.md §3, §8): the sensitive,
// free-text columns — window title, URL, and the meta blob — are encrypted at
// rest with AES-256-GCM using a key from the OS keychain. The structured,
// low-sensitivity columns (timestamps, source, category, project, duration,
// is_idle) stay plaintext so the dashboard can range- and category-query and
// aggregate without ever holding the key.
//
// The store is the single writer/reader of the local DB. Append is idempotent
// on event_id (collectors may retry), matching the ingest convention.
package store

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"time"

	"github.com/Aditya1711-tech/cadence/agent/internal/crypto"
	"github.com/Aditya1711-tech/cadence/agent/internal/event"

	_ "modernc.org/sqlite" // pure-Go SQLite driver, registered as "sqlite"
)

const schema = `
CREATE TABLE IF NOT EXISTS events (
    event_id    TEXT    PRIMARY KEY,
    schema_ver  INTEGER NOT NULL,
    source      TEXT    NOT NULL,
    member_id   TEXT    NOT NULL,
    ts_start_ms INTEGER NOT NULL,
    ts_end_ms   INTEGER NOT NULL,
    duration_ms INTEGER NOT NULL,
    app         TEXT    NOT NULL,
    project     TEXT,
    category    TEXT,
    is_idle     INTEGER NOT NULL,
    title_enc   BLOB,
    url_enc     BLOB,
    meta_enc    BLOB    NOT NULL,
    created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_events_ts_start ON events (ts_start_ms);
CREATE INDEX IF NOT EXISTS idx_events_category ON events (category);
`

// Store is an open handle to the local encrypted event store.
type Store struct {
	db  *sql.DB
	key []byte
}

// Open opens (creating if needed) the SQLite database at dbPath and applies the
// schema. key must be crypto.KeySize bytes — typically from
// keyring.LoadOrCreateKey.
func Open(dbPath string, key []byte) (*Store, error) {
	if len(key) != crypto.KeySize {
		return nil, crypto.ErrKeySize
	}
	db, err := sql.Open("sqlite", dbPath)
	if err != nil {
		return nil, fmt.Errorf("store: open %s: %w", dbPath, err)
	}
	// One writer at a time keeps SQLite happy; WAL allows readers alongside.
	db.SetMaxOpenConns(1)
	for _, pragma := range []string{
		"PRAGMA journal_mode=WAL",
		"PRAGMA busy_timeout=5000",
		"PRAGMA synchronous=NORMAL",
		"PRAGMA foreign_keys=ON",
	} {
		if _, err := db.Exec(pragma); err != nil {
			db.Close()
			return nil, fmt.Errorf("store: %s: %w", pragma, err)
		}
	}
	if _, err := db.Exec(schema); err != nil {
		db.Close()
		return nil, fmt.Errorf("store: apply schema: %w", err)
	}
	return &Store{db: db, key: key}, nil
}

// Close releases the database handle.
func (s *Store) Close() error { return s.db.Close() }

// Append persists e. It validates the event, encrypts the sensitive columns,
// and is idempotent on event_id (a repeated event_id is silently ignored).
func (s *Store) Append(e *event.Event) error {
	if err := e.Validate(); err != nil {
		return err
	}
	titleEnc, err := s.sealPtr(e.Title)
	if err != nil {
		return fmt.Errorf("store: seal title: %w", err)
	}
	urlEnc, err := s.sealPtr(e.URL)
	if err != nil {
		return fmt.Errorf("store: seal url: %w", err)
	}
	meta := e.Meta
	if meta == nil {
		meta = event.Meta{}
	}
	metaJSON, err := json.Marshal(meta)
	if err != nil {
		return fmt.Errorf("store: marshal meta: %w", err)
	}
	metaEnc, err := crypto.Seal(s.key, metaJSON)
	if err != nil {
		return fmt.Errorf("store: seal meta: %w", err)
	}

	_, err = s.db.Exec(
		`INSERT INTO events (
            event_id, schema_ver, source, member_id, ts_start_ms, ts_end_ms,
            duration_ms, app, project, category, is_idle, title_enc, url_enc,
            meta_enc, created_at
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT(event_id) DO NOTHING`,
		e.EventID, e.SchemaVer, string(e.Source), e.MemberID,
		e.TsStart.UnixMilli(), e.TsEnd.UnixMilli(), e.DurationMs, e.App,
		strPtr(e.Project), catPtr(e.Category), boolToInt(e.IsIdle),
		titleEnc, urlEnc, metaEnc, time.Now().UnixMilli(),
	)
	if err != nil {
		return fmt.Errorf("store: insert event: %w", err)
	}
	return nil
}

// Query returns events whose ts_start falls in [from, to), ordered ascending by
// ts_start. Encrypted columns are decrypted before return.
func (s *Store) Query(from, to time.Time) ([]event.Event, error) {
	rows, err := s.db.Query(
		`SELECT event_id, schema_ver, source, member_id, ts_start_ms, ts_end_ms,
                duration_ms, app, project, category, is_idle, title_enc, url_enc, meta_enc
         FROM events
         WHERE ts_start_ms >= ? AND ts_start_ms < ?
         ORDER BY ts_start_ms ASC`,
		from.UnixMilli(), to.UnixMilli(),
	)
	if err != nil {
		return nil, fmt.Errorf("store: query: %w", err)
	}
	defer rows.Close()

	var out []event.Event
	for rows.Next() {
		e, err := s.scanEvent(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("store: iterate rows: %w", err)
	}
	return out, nil
}

// Count returns the total number of stored events. Useful for health checks.
func (s *Store) Count() (int, error) {
	var n int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM events`).Scan(&n); err != nil {
		return 0, fmt.Errorf("store: count: %w", err)
	}
	return n, nil
}

func (s *Store) scanEvent(rows *sql.Rows) (event.Event, error) {
	var (
		e         event.Event
		source    string
		tsStartMs int64
		tsEndMs   int64
		project   sql.NullString
		category  sql.NullString
		isIdle    int
		titleEnc  []byte
		urlEnc    []byte
		metaEnc   []byte
	)
	if err := rows.Scan(
		&e.EventID, &e.SchemaVer, &source, &e.MemberID, &tsStartMs, &tsEndMs,
		&e.DurationMs, &e.App, &project, &category, &isIdle, &titleEnc, &urlEnc, &metaEnc,
	); err != nil {
		return e, fmt.Errorf("store: scan: %w", err)
	}
	e.Source = event.Source(source)
	e.TsStart = time.UnixMilli(tsStartMs).UTC()
	e.TsEnd = time.UnixMilli(tsEndMs).UTC()
	e.IsIdle = isIdle != 0
	if project.Valid {
		e.Project = event.Ptr(project.String)
	}
	if category.Valid {
		e.Category = event.Ptr(event.Category(category.String))
	}

	title, err := s.openPtr(titleEnc)
	if err != nil {
		return e, fmt.Errorf("store: open title: %w", err)
	}
	e.Title = title

	url, err := s.openPtr(urlEnc)
	if err != nil {
		return e, fmt.Errorf("store: open url: %w", err)
	}
	e.URL = url

	metaJSON, err := crypto.Open(s.key, metaEnc)
	if err != nil {
		return e, fmt.Errorf("store: open meta: %w", err)
	}
	if err := json.Unmarshal(metaJSON, &e.Meta); err != nil {
		return e, fmt.Errorf("store: unmarshal meta: %w", err)
	}
	return e, nil
}

// sealPtr seals a nullable string: nil pointer => nil blob (stored as NULL).
func (s *Store) sealPtr(p *string) ([]byte, error) {
	if p == nil {
		return nil, nil
	}
	return crypto.Seal(s.key, []byte(*p))
}

// openPtr reverses sealPtr: nil blob => nil pointer.
func (s *Store) openPtr(blob []byte) (*string, error) {
	if blob == nil {
		return nil, nil
	}
	pt, err := crypto.Open(s.key, blob)
	if err != nil {
		return nil, err
	}
	return event.Ptr(string(pt)), nil
}

func strPtr(p *string) any {
	if p == nil {
		return nil
	}
	return *p
}

func catPtr(p *event.Category) any {
	if p == nil {
		return nil
	}
	return string(*p)
}

func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

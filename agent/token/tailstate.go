package token

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
)

// tailState persists a per-file byte offset (the cursor) so the watcher reads
// only new bytes and never reparses or double-counts across restarts
// (docs/P2-C.1, "Incremental tail"). It is a small JSON file in the daemon's
// store dir.
type tailState struct {
	path    string
	mu      sync.Mutex
	Offsets map[string]int64 `json:"offsets"`
}

func loadTailState(path string) (*tailState, error) {
	s := &tailState{path: path, Offsets: map[string]int64{}}
	b, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return s, nil
	}
	if err != nil {
		return nil, err
	}
	// A corrupt cursor file shouldn't wedge the watcher; start fresh on parse error.
	_ = json.Unmarshal(b, s)
	if s.Offsets == nil {
		s.Offsets = map[string]int64{}
	}
	return s, nil
}

func (s *tailState) get(file string) int64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.Offsets[file]
}

func (s *tailState) set(file string, off int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.Offsets[file] = off
}

// save atomically rewrites the cursor file (write-temp-then-rename).
func (s *tailState) save() error {
	s.mu.Lock()
	b, err := json.MarshalIndent(s, "", "  ")
	s.mu.Unlock()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(s.path), 0o700); err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}

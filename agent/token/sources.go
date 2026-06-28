package token

import (
	"io/fs"
	"os"
	"path/filepath"
	"strings"
)

// Parser consumes a chunk of complete log lines and returns usage Records.
// A fresh parser is created per file so stateful parsers (Codex) carry the
// file's cwd/model across incremental reads.
type Parser interface {
	Parse(data []byte) ([]Record, error)
}

// Source is one supported coding-agent tool: where it logs, how to enumerate
// its session files, and how to build a per-file parser. Detection is
// zero-config — Detect probes well-known paths (with env overrides).
type Source interface {
	Name() string
	// Detect returns the log root and whether the tool appears installed.
	Detect() (root string, ok bool)
	// Files lists session log files under root.
	Files(root string) ([]string, error)
	// NewParser returns a fresh per-file parser.
	NewParser() Parser
}

// AllSources returns every implemented source, honoring env overrides. Cursor is
// intentionally absent: its usage is server-side, so it can't be tailed locally
// (docs/P2-C.1) — it's a future Admin-API connector, not a log source here.
func AllSources() []Source {
	return []Source{
		ClaudeCodeSource{},
		CodexSource{defaultModel: os.Getenv("CADENCE_CODEX_DEFAULT_MODEL")},
	}
}

// --- Claude Code ---

// ClaudeCodeSource tails ~/.claude/projects/<slug>/<sessionId>.jsonl.
type ClaudeCodeSource struct{}

func (ClaudeCodeSource) Name() string { return "claude_code" }

func (ClaudeCodeSource) Detect() (string, bool) {
	if d := os.Getenv("CADENCE_CLAUDE_CODE_LOG_DIR"); d != "" {
		return d, dirExists(d)
	}
	if cfg := os.Getenv("CLAUDE_CONFIG_DIR"); cfg != "" {
		d := filepath.Join(cfg, "projects")
		return d, dirExists(d)
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", false
	}
	d := filepath.Join(home, ".claude", "projects")
	return d, dirExists(d)
}

// Files returns root/<project-slug>/*.jsonl (one directory level).
func (ClaudeCodeSource) Files(root string) ([]string, error) {
	return filepath.Glob(filepath.Join(root, "*", "*.jsonl"))
}

func (ClaudeCodeSource) NewParser() Parser { return ClaudeCodeParser{} }

// --- Codex ---

// CodexSource tails ~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl.
type CodexSource struct{ defaultModel string }

func (CodexSource) Name() string { return "codex" }

func (CodexSource) Detect() (string, bool) {
	if d := os.Getenv("CADENCE_CODEX_LOG_DIR"); d != "" {
		return d, dirExists(d)
	}
	if h := os.Getenv("CODEX_HOME"); h != "" {
		d := filepath.Join(h, "sessions")
		return d, dirExists(d)
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", false
	}
	d := filepath.Join(home, ".codex", "sessions")
	return d, dirExists(d)
}

// Files walks the date-nested tree for rollout-*.jsonl session files.
func (CodexSource) Files(root string) ([]string, error) {
	var out []string
	err := filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil // skip unreadable subtrees rather than abort the walk
		}
		if d.IsDir() {
			return nil
		}
		name := d.Name()
		if strings.HasPrefix(name, "rollout-") && strings.HasSuffix(name, ".jsonl") {
			out = append(out, path)
		}
		return nil
	})
	return out, err
}

func (s CodexSource) NewParser() Parser { return NewCodexParser(s.defaultModel) }

func dirExists(p string) bool {
	info, err := os.Stat(p)
	return err == nil && info.IsDir()
}

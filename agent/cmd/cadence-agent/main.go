// Command cadence-agent is the local Cadence daemon. In Phase 1 it opens the
// encrypted local store and serves the loopback API that collectors POST to and
// the dashboard reads from. Active-window collection and service lifecycle land
// in later P1-A tasks.
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/Aditya1711-tech/cadence/agent/internal/api"
	"github.com/Aditya1711-tech/cadence/agent/internal/classify"
	"github.com/Aditya1711-tech/cadence/agent/internal/keyring"
	"github.com/Aditya1711-tech/cadence/agent/internal/redact"
	"github.com/Aditya1711-tech/cadence/agent/internal/store"
)

const (
	defaultPort     = "47821"
	defaultService  = "com.cadence.agent"
	keychainAccount = "store-key"
)

func main() {
	log := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelInfo}))

	if err := run(log); err != nil {
		log.Error("agent exited with error", "err", err)
		os.Exit(1)
	}
}

func run(log *slog.Logger) error {
	port := envOr("CADENCE_AGENT_PORT", defaultPort)
	service := envOr("CADENCE_KEYCHAIN_SERVICE", defaultService)
	dbPath, err := resolveDBPath()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(dbPath), 0o700); err != nil {
		return err
	}

	key, err := keyring.LoadOrCreateKey(keyring.OS{}, service, keychainAccount)
	if err != nil {
		return err
	}

	st, err := store.Open(dbPath, key)
	if err != nil {
		return err
	}
	defer st.Close()
	log.Info("local store ready", "path", dbPath)

	classifier, err := loadClassifier(log)
	if err != nil {
		return err
	}
	redactor, err := loadRedactor(log)
	if err != nil {
		return err
	}

	addr := "127.0.0.1:" + port
	srv := &http.Server{
		Addr: addr,
		Handler: api.New(st, api.Options{
			Classifier: classifier,
			Redactor:   redactor,
			Logger:     log,
		}).Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	errCh := make(chan error, 1)
	go func() {
		log.Info("serving local API", "addr", addr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
	}()

	select {
	case err := <-errCh:
		return err
	case <-ctx.Done():
		log.Info("shutting down")
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		return srv.Shutdown(shutdownCtx)
	}
}

// loadClassifier returns the event classifier. With CADENCE_RULES_PATH set, it
// loads that JSON ruleset — scaffolding it with the built-in default on first
// run if the file is missing, so users have something to edit. Unset uses the
// built-in default ruleset.
func loadClassifier(log *slog.Logger) (*classify.Classifier, error) {
	path := os.Getenv("CADENCE_RULES_PATH")
	if path == "" {
		return classify.Default(), nil
	}
	if _, err := os.Stat(path); errors.Is(err, os.ErrNotExist) {
		b, err := classify.DefaultRulesetJSON()
		if err != nil {
			return nil, err
		}
		if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
			return nil, err
		}
		if err := os.WriteFile(path, b, 0o600); err != nil {
			return nil, err
		}
		log.Info("wrote default classifier ruleset (edit to customize)", "path", path)
		return classify.Default(), nil
	}
	c, err := classify.Load(path)
	if err != nil {
		return nil, err
	}
	log.Info("loaded classifier ruleset", "path", path)
	return c, nil
}

// loadRedactor builds the local redaction list. With CADENCE_REDACT_PATH set it
// loads that JSON list — scaffolding an empty one on first run — else redaction
// is disabled (no patterns).
func loadRedactor(log *slog.Logger) (*redact.Redactor, error) {
	path := os.Getenv("CADENCE_REDACT_PATH")
	if path == "" {
		return redact.New(nil)
	}
	if _, err := os.Stat(path); errors.Is(err, os.ErrNotExist) {
		b, err := redact.DefaultConfigJSON()
		if err != nil {
			return nil, err
		}
		if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
			return nil, err
		}
		if err := os.WriteFile(path, b, 0o600); err != nil {
			return nil, err
		}
		log.Info("wrote empty redaction list (add regex patterns to redact)", "path", path)
		return redact.New(nil)
	}
	r, err := redact.Load(path)
	if err != nil {
		return nil, err
	}
	log.Info("loaded redaction list", "path", path, "enabled", r.Enabled())
	return r, nil
}

// resolveDBPath returns CADENCE_DB_PATH or a per-user default under the OS
// config dir, e.g. ~/.config/cadence/cadence.db.
func resolveDBPath() (string, error) {
	if p := os.Getenv("CADENCE_DB_PATH"); p != "" {
		return p, nil
	}
	dir, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "cadence", "cadence.db"), nil
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

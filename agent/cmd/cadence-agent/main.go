// Command cadence-agent is the local Cadence daemon. In Phase 1 it opens the
// encrypted local store and serves the loopback API that collectors POST to and
// the dashboard reads from. Active-window collection and service lifecycle land
// in later P1-A tasks.
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"syscall"
	"time"

	"github.com/Aditya1711-tech/cadence/agent/internal/api"
	"github.com/Aditya1711-tech/cadence/agent/internal/classify"
	"github.com/Aditya1711-tech/cadence/agent/internal/collector"
	"github.com/Aditya1711-tech/cadence/agent/internal/event"
	"github.com/Aditya1711-tech/cadence/agent/internal/keyring"
	"github.com/Aditya1711-tech/cadence/agent/internal/redact"
	"github.com/Aditya1711-tech/cadence/agent/internal/store"
	cloudsync "github.com/Aditya1711-tech/cadence/agent/sync"
)

const (
	defaultPort      = "47821"
	defaultService   = "com.cadence.agent"
	keychainAccount  = "store-key"
	defaultCloudBase = "http://localhost:8080"
	syncDBName       = "cadence-sync.db"
)

func main() {
	log := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelInfo}))

	// Subcommands (P2-B): `enroll <code>` and `status`. With no args, run the daemon.
	if len(os.Args) > 1 {
		if err := runSubcommand(log, os.Args[1], os.Args[2:]); err != nil {
			log.Error("command failed", "command", os.Args[1], "err", err)
			os.Exit(1)
		}
		return
	}

	if err := run(log); err != nil {
		log.Error("agent exited with error", "err", err)
		os.Exit(1)
	}
}

// runSubcommand handles the P2-B cloud-sync CLI: device enrollment and status.
// These are thin shells over the /agent/sync package; all logic lives there.
func runSubcommand(log *slog.Logger, cmd string, args []string) error {
	service := envOr("CADENCE_KEYCHAIN_SERVICE", defaultService)
	switch cmd {
	case "enroll":
		if len(args) < 1 || args[0] == "" {
			return fmt.Errorf("usage: cadence-agent enroll <code>")
		}
		keys := cloudsync.NewKeystore(keyring.OS{}, service)
		client := cloudsync.NewClient(envOr("CADENCE_CLOUD_BASE", defaultCloudBase), nil)
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		creds, err := cloudsync.Enroll(ctx, client, keys, args[0])
		if err != nil {
			return err
		}
		log.Info("device enrolled; cloud sync will begin shortly", "member_id", creds.MemberID)
		return nil
	case "status":
		keys := cloudsync.NewKeystore(keyring.OS{}, service)
		var state *cloudsync.State
		if p, err := resolveSyncDBPath(); err == nil {
			if s, oerr := cloudsync.OpenState(p); oerr == nil {
				state = s
				defer state.Close()
			}
		}
		snap, err := cloudsync.Snapshot(keys, state)
		if err != nil {
			return err
		}
		log.Info("agent status",
			"enrolled", snap.Enrolled, "member_id", snap.MemberID, "synced_rows", snap.SyncedRows)
		return nil
	default:
		return fmt.Errorf("unknown command %q (want: enroll, status)", cmd)
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

	startCollector(ctx, log, service, addr)
	startSync(ctx, log, service, st)

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

// startCollector starts the OS active-window + idle collector if this platform
// has a backend, posting events to the local API. On unsupported platforms it
// logs and returns, leaving the API serving without an OS source.
func startCollector(ctx context.Context, log *slog.Logger, service, addr string) {
	w, idle, err := collector.NewPlatform()
	if err != nil {
		log.Warn("OS collector unavailable on this platform; serving without it", "err", err)
		return
	}
	memberID, err := loadOrCreateMemberID(keyring.OS{}, service)
	if err != nil {
		log.Error("could not resolve member id; OS collector disabled", "err", err)
		return
	}
	sink := collector.NewHTTPSink("http://"+addr, nil)
	col := collector.New(w, idle, sink, collector.Config{MemberID: memberID, Logger: log})
	go func() {
		log.Info("OS collector started", "member_id", memberID)
		_ = col.Run(ctx)
	}()
}

// startSync starts the P2-B cloud-sync loop in the background. It is always
// started; when the device is not yet enrolled the loop simply stands by
// (reloading credentials each cycle), so `cadence-agent enroll` in a separate
// process turns sync on without restarting the daemon. The sidecar state DB is
// closed on shutdown. Failures to set up are logged, not fatal — local-only
// operation continues regardless.
func startSync(ctx context.Context, log *slog.Logger, service string, reader cloudsync.EventReader) {
	syncDB, err := resolveSyncDBPath()
	if err != nil {
		log.Warn("cloud sync disabled: cannot resolve sync db path", "err", err)
		return
	}
	state, err := cloudsync.OpenState(syncDB)
	if err != nil {
		log.Warn("cloud sync disabled: cannot open sync state", "err", err)
		return
	}
	base := envOr("CADENCE_CLOUD_BASE", defaultCloudBase)
	keys := cloudsync.NewKeystore(keyring.OS{}, service)
	client := cloudsync.NewClient(base, nil)
	syncer := cloudsync.NewSyncer(reader, state, keys, client, cloudsync.Config{
		Interval: syncInterval(),
		Logger:   log,
	})
	if keys.Enrolled() {
		log.Info("cloud sync enabled", "member_id", keys.MemberID(), "base", base)
	} else {
		log.Info("cloud sync standing by (device not enrolled; run `cadence-agent enroll <code>`)")
	}
	go func() {
		defer state.Close()
		syncer.Run(ctx)
	}()
}

// syncInterval returns the sync loop cadence from CADENCE_SYNC_INTERVAL_SEC
// (seconds), defaulting to 5 minutes.
func syncInterval() time.Duration {
	if v := os.Getenv("CADENCE_SYNC_INTERVAL_SEC"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			return time.Duration(n) * time.Second
		}
	}
	return 5 * time.Minute
}

// resolveSyncDBPath returns CADENCE_SYNC_DB_PATH or a sibling of the main store
// DB (e.g. ~/.config/cadence/cadence-sync.db).
func resolveSyncDBPath() (string, error) {
	if p := os.Getenv("CADENCE_SYNC_DB_PATH"); p != "" {
		return p, nil
	}
	dbPath, err := resolveDBPath()
	if err != nil {
		return "", err
	}
	return filepath.Join(filepath.Dir(dbPath), syncDBName), nil
}

// loadOrCreateMemberID returns a stable local member identity, from
// CADENCE_MEMBER_ID or a uuid persisted in the keychain on first run.
func loadOrCreateMemberID(kr keyring.Keyring, service string) (string, error) {
	if v := os.Getenv("CADENCE_MEMBER_ID"); v != "" {
		return v, nil
	}
	id, err := kr.Get(service, "member-id")
	if err == nil {
		return id, nil
	}
	if !errors.Is(err, keyring.ErrNotFound) {
		return "", err
	}
	newID, err := event.NewID()
	if err != nil {
		return "", err
	}
	if err := kr.Set(service, "member-id", newID); err != nil {
		return "", err
	}
	return newID, nil
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

// Command cadence-token is the standalone AI token watcher (stream P2-C). It
// tails installed coding-agent CLI logs and POSTs token-usage events to the
// running daemon's loopback /events route — the same route every other
// collector uses. Shipping it as its own binary means P2-C needs no change to
// the P1-A-owned daemon main.go; an eventual in-daemon wire is filed as a NEEDS
// line in PROGRESS.md and would call token.NewWatcher(...).Run the same way.
package main

import (
	"context"
	"errors"
	"log/slog"
	"os"
	"os/signal"
	"strings"
	"syscall"

	"github.com/Aditya1711-tech/cadence/agent/internal/collector"
	"github.com/Aditya1711-tech/cadence/agent/internal/event"
	"github.com/Aditya1711-tech/cadence/agent/internal/keyring"
	"github.com/Aditya1711-tech/cadence/agent/token"
)

const (
	defaultPort    = "47821"
	defaultService = "com.cadence.agent"
)

func main() {
	log := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelInfo}))
	if err := run(log); err != nil {
		log.Error("token watcher exited with error", "err", err)
		os.Exit(1)
	}
}

func run(log *slog.Logger) error {
	port := envOr("CADENCE_AGENT_PORT", defaultPort)
	service := envOr("CADENCE_KEYCHAIN_SERVICE", defaultService)

	memberID, err := loadOrCreateMemberID(keyring.OS{}, service)
	if err != nil {
		return err
	}

	pricing, err := loadPricing(log)
	if err != nil {
		return err
	}

	sink := collector.NewHTTPSink("http://127.0.0.1:"+port, nil)
	w, err := token.NewWatcher(token.AllSources(), token.Sink(sink), token.Config{
		MemberID: memberID,
		Pricing:  pricing,
		Enabled:  enabledSources(),
		StateDir: os.Getenv("CADENCE_TOKEN_STATE_DIR"), // empty => OS config dir
		Logger:   log,
	})
	if err != nil {
		return err
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	log.Info("token watcher started", "member_id", memberID, "target", "127.0.0.1:"+port)
	return w.Run(ctx)
}

// loadPricing loads the per-model price table from CADENCE_TOKEN_PRICING_PATH,
// or the built-in defaults when unset.
func loadPricing(log *slog.Logger) (*token.Table, error) {
	path := os.Getenv("CADENCE_TOKEN_PRICING_PATH")
	if path == "" {
		return token.DefaultTable(), nil
	}
	t, err := token.LoadTable(path)
	if err != nil {
		return nil, err
	}
	log.Info("loaded token price table", "path", path)
	return t, nil
}

// enabledSources parses CADENCE_TOKEN_SOURCES (default: all). Names not
// implemented as local sources (e.g. "cursor") are simply ignored.
func enabledSources() map[string]bool {
	v := os.Getenv("CADENCE_TOKEN_SOURCES")
	if v == "" {
		return nil // nil => all detected sources active
	}
	set := map[string]bool{}
	for _, name := range strings.Split(v, ",") {
		if n := strings.TrimSpace(name); n != "" {
			set[n] = true
		}
	}
	return set
}

// loadOrCreateMemberID mirrors the daemon: CADENCE_MEMBER_ID, else a uuid in the
// keychain. All collectors on a machine share this id (the cloud ingest stamps
// the canonical member_id from the JWT regardless — see PROGRESS coordination).
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

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

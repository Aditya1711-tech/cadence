// Package keyring stores the local store's master encryption key in the OS
// keychain (macOS Keychain, Linux Secret Service, Windows Credential Manager)
// and loads-or-creates it on first run.
//
// The key never touches disk in plaintext: it is generated with crypto/rand,
// base64-encoded, and handed to the OS secret store under a service+account
// name. Callers depend on the small Keyring interface, so tests (and headless
// CI) can inject an in-memory fake instead of touching the real OS keychain.
package keyring

import (
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"io"

	"github.com/Aditya1711-tech/cadence/agent/internal/crypto"
)

// ErrNotFound is returned by Keyring.Get when no secret exists for the
// service/account pair. Implementations must map their "missing" error to this.
var ErrNotFound = errors.New("keyring: secret not found")

// Keyring is the minimal secret-store surface the agent needs.
type Keyring interface {
	Get(service, account string) (string, error)
	Set(service, account, secret string) error
}

// LoadOrCreateKey returns the 32-byte master key for (service, account),
// generating and persisting a new one in kr if none exists yet. The returned
// key is always exactly crypto.KeySize bytes.
func LoadOrCreateKey(kr Keyring, service, account string) ([]byte, error) {
	enc, err := kr.Get(service, account)
	switch {
	case err == nil:
		key, derr := base64.StdEncoding.DecodeString(enc)
		if derr != nil {
			return nil, fmt.Errorf("keyring: stored key is not valid base64: %w", derr)
		}
		if len(key) != crypto.KeySize {
			return nil, fmt.Errorf("keyring: stored key is %d bytes, want %d", len(key), crypto.KeySize)
		}
		return key, nil
	case errors.Is(err, ErrNotFound):
		key := make([]byte, crypto.KeySize)
		if _, rerr := io.ReadFull(rand.Reader, key); rerr != nil {
			return nil, fmt.Errorf("keyring: generate key: %w", rerr)
		}
		if serr := kr.Set(service, account, base64.StdEncoding.EncodeToString(key)); serr != nil {
			return nil, fmt.Errorf("keyring: persist new key: %w", serr)
		}
		return key, nil
	default:
		return nil, fmt.Errorf("keyring: read secret: %w", err)
	}
}

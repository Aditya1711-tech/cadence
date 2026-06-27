package keyring

import (
	"bytes"
	"testing"

	"github.com/Aditya1711-tech/cadence/agent/internal/crypto"
)

const (
	svc = "com.cadence.agent"
	acc = "store-key"
)

func TestLoadOrCreateKey_CreatesThenLoadsSame(t *testing.T) {
	kr := NewMemory()

	k1, err := LoadOrCreateKey(kr, svc, acc)
	if err != nil {
		t.Fatalf("first load: %v", err)
	}
	if len(k1) != crypto.KeySize {
		t.Fatalf("key size %d, want %d", len(k1), crypto.KeySize)
	}

	k2, err := LoadOrCreateKey(kr, svc, acc)
	if err != nil {
		t.Fatalf("second load: %v", err)
	}
	if !bytes.Equal(k1, k2) {
		t.Fatal("second load returned a different key; should be stable")
	}
}

func TestLoadOrCreateKey_RejectsBadStoredKey(t *testing.T) {
	kr := NewMemory()
	if err := kr.Set(svc, acc, "not-base64!!"); err != nil {
		t.Fatalf("set: %v", err)
	}
	if _, err := LoadOrCreateKey(kr, svc, acc); err == nil {
		t.Fatal("expected error for non-base64 stored key")
	}
}

func TestMemoryGetMissing(t *testing.T) {
	kr := NewMemory()
	if _, err := kr.Get(svc, acc); err != ErrNotFound {
		t.Fatalf("expected ErrNotFound, got %v", err)
	}
}

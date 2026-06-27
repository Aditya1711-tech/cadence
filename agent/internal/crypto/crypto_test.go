package crypto

import (
	"bytes"
	"crypto/rand"
	"io"
	"testing"
)

func testKey(t *testing.T) []byte {
	t.Helper()
	k := make([]byte, KeySize)
	if _, err := io.ReadFull(rand.Reader, k); err != nil {
		t.Fatalf("gen key: %v", err)
	}
	return k
}

func TestSealOpenRoundTrip(t *testing.T) {
	key := testKey(t)
	for _, pt := range [][]byte{
		[]byte("auth.ts — cadence-api"),
		[]byte("https://github.com/org/repo/pull/42"),
		[]byte(`{"model":"claude-sonnet-4","tokens_in":12000}`),
		{},  // empty plaintext must round-trip
		nil, // nil plaintext
	} {
		blob, err := Seal(key, pt)
		if err != nil {
			t.Fatalf("seal: %v", err)
		}
		if len(blob) < NonceSize {
			t.Fatalf("sealed blob too short: %d", len(blob))
		}
		got, err := Open(key, blob)
		if err != nil {
			t.Fatalf("open: %v", err)
		}
		if !bytes.Equal(got, pt) && !(len(got) == 0 && len(pt) == 0) {
			t.Fatalf("round-trip mismatch: got %q want %q", got, pt)
		}
	}
}

func TestNonceIsRandom(t *testing.T) {
	key := testKey(t)
	a, _ := Seal(key, []byte("same"))
	b, _ := Seal(key, []byte("same"))
	if bytes.Equal(a, b) {
		t.Fatal("two seals of identical plaintext produced identical ciphertext (nonce reuse)")
	}
}

func TestOpenWithWrongKeyFails(t *testing.T) {
	blob, _ := Seal(testKey(t), []byte("secret"))
	if _, err := Open(testKey(t), blob); err != ErrMalformed {
		t.Fatalf("expected ErrMalformed with wrong key, got %v", err)
	}
}

func TestOpenTamperedFails(t *testing.T) {
	key := testKey(t)
	blob, _ := Seal(key, []byte("secret"))
	blob[len(blob)-1] ^= 0xff // flip a tag bit
	if _, err := Open(key, blob); err != ErrMalformed {
		t.Fatalf("expected ErrMalformed on tamper, got %v", err)
	}
}

func TestBadKeySize(t *testing.T) {
	if _, err := Seal([]byte("short"), []byte("x")); err != ErrKeySize {
		t.Fatalf("expected ErrKeySize, got %v", err)
	}
	if _, err := Open(make([]byte, 16), make([]byte, 40)); err != ErrKeySize {
		t.Fatalf("expected ErrKeySize, got %v", err)
	}
}

func TestOpenTooShort(t *testing.T) {
	if _, err := Open(testKey(t), []byte("abc")); err != ErrMalformed {
		t.Fatalf("expected ErrMalformed for short blob, got %v", err)
	}
}

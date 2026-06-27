// Package crypto provides authenticated symmetric encryption for the local
// store's sensitive columns (window titles, URLs, and the meta blob).
//
// We use AES-256-GCM with a random 96-bit nonce per value. The 256-bit key
// lives in the OS keychain (see package keyring); this package never persists
// or derives keys — it only seals and opens byte slices. The on-disk format of
// a sealed value is: nonce(12) || ciphertext || GCM-tag(16).
//
// This is the "app-level column encryption" approach chosen because the pinned
// driver (modernc.org/sqlite, pure-Go/no-CGO) has no SQLCipher-style full-file
// encryption. Structured/queryable columns stay plaintext so the dashboard can
// range- and category-query without the key; only PII-bearing free text is
// encrypted at rest.
package crypto

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"errors"
	"fmt"
	"io"
)

// KeySize is the required key length in bytes (AES-256).
const KeySize = 32

// NonceSize is the GCM nonce length in bytes.
const NonceSize = 12

// ErrKeySize is returned when a key is not exactly KeySize bytes.
var ErrKeySize = fmt.Errorf("crypto: key must be %d bytes", KeySize)

// ErrMalformed is returned when a sealed blob is too short or fails
// authentication on open.
var ErrMalformed = errors.New("crypto: malformed or tampered ciphertext")

func newGCM(key []byte) (cipher.AEAD, error) {
	if len(key) != KeySize {
		return nil, ErrKeySize
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(block)
}

// Seal encrypts plaintext with key, returning nonce||ciphertext||tag. A fresh
// random nonce is used for every call.
func Seal(key, plaintext []byte) ([]byte, error) {
	gcm, err := newGCM(key)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, NonceSize)
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return nil, fmt.Errorf("crypto: read nonce: %w", err)
	}
	// Seal appends the ciphertext to nonce, so the nonce prefixes the result.
	return gcm.Seal(nonce, nonce, plaintext, nil), nil
}

// Open reverses Seal. It authenticates and decrypts blob, returning an error if
// the key is wrong or the data was tampered with.
func Open(key, blob []byte) ([]byte, error) {
	gcm, err := newGCM(key)
	if err != nil {
		return nil, err
	}
	if len(blob) < NonceSize {
		return nil, ErrMalformed
	}
	nonce, ct := blob[:NonceSize], blob[NonceSize:]
	pt, err := gcm.Open(nil, nonce, ct, nil)
	if err != nil {
		return nil, ErrMalformed
	}
	return pt, nil
}

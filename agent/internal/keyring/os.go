package keyring

import (
	"errors"

	zk "github.com/zalando/go-keyring"
)

// OS is the production Keyring backed by the platform secret store via
// zalando/go-keyring: macOS Keychain (security CLI), Linux Secret Service
// (D-Bus), Windows Credential Manager (wincred). No CGO.
type OS struct{}

// Get implements Keyring, mapping the library's not-found error to ErrNotFound.
func (OS) Get(service, account string) (string, error) {
	secret, err := zk.Get(service, account)
	if errors.Is(err, zk.ErrNotFound) {
		return "", ErrNotFound
	}
	return secret, err
}

// Set implements Keyring.
func (OS) Set(service, account, secret string) error {
	return zk.Set(service, account, secret)
}

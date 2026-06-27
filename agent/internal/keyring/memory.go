package keyring

import "sync"

// Memory is an in-memory Keyring for tests and headless environments where the
// OS keychain is unavailable. It is safe for concurrent use. It is NOT for
// production: secrets live only in process memory.
type Memory struct {
	mu sync.Mutex
	m  map[string]string
}

// NewMemory returns an empty in-memory keyring.
func NewMemory() *Memory {
	return &Memory{m: make(map[string]string)}
}

func key(service, account string) string { return service + "\x00" + account }

// Get implements Keyring.
func (k *Memory) Get(service, account string) (string, error) {
	k.mu.Lock()
	defer k.mu.Unlock()
	v, ok := k.m[key(service, account)]
	if !ok {
		return "", ErrNotFound
	}
	return v, nil
}

// Set implements Keyring.
func (k *Memory) Set(service, account, secret string) error {
	k.mu.Lock()
	defer k.mu.Unlock()
	k.m[key(service, account)] = secret
	return nil
}

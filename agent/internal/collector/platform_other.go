//go:build !windows && !darwin && !linux

package collector

import (
	"errors"
	"runtime"
)

// NewPlatform is unsupported on this GOOS; the daemon serves the API without an
// OS collector.
func NewPlatform() (Watcher, IdleSource, error) {
	return nil, nil, errors.New("collector: no OS backend for " + runtime.GOOS)
}

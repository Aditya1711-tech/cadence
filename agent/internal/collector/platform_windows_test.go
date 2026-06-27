//go:build windows

package collector

import "testing"

// TestWindowsBackendReadsRealWindow exercises the real user32/kernel32 calls on
// Windows. It is excluded on other OSes (build tag); the macOS/Linux backends
// are verified on those platforms.
func TestWindowsBackendReadsRealWindow(t *testing.T) {
	w, idle, err := NewPlatform()
	if err != nil {
		t.Fatalf("NewPlatform: %v", err)
	}
	win, err := w.Active()
	if err != nil {
		t.Fatalf("Active: %v", err)
	}
	if win.App == "" {
		t.Errorf("expected a non-empty foreground app name")
	}
	s, err := idle.IdleSeconds()
	if err != nil {
		t.Fatalf("IdleSeconds: %v", err)
	}
	if s < 0 {
		t.Errorf("idle seconds should be non-negative, got %v", s)
	}
	t.Logf("active app=%q title=%q hasTitle=%v idle=%.1fs", win.App, win.Title, win.HasTitle, s)
}

package cloudsync

import (
	"math/rand"
	"testing"
	"time"
)

func TestBackoffBoundsAndGrowth(t *testing.T) {
	base := 1 * time.Second
	max := 30 * time.Second
	b := NewBackoff(base, max, 2.0, rand.New(rand.NewSource(42)))

	// Each delay must be within [base, cap_n], cap_n = min(base*2^(n-1), max).
	caps := []time.Duration{1 * time.Second, 2 * time.Second, 4 * time.Second, 8 * time.Second, 16 * time.Second, 30 * time.Second, 30 * time.Second}
	for i, capWant := range caps {
		d := b.Next()
		if d < base {
			t.Fatalf("attempt %d: delay %v < base %v", i+1, d, base)
		}
		if d > capWant {
			t.Fatalf("attempt %d: delay %v > cap %v", i+1, d, capWant)
		}
	}
	if b.Failures() != len(caps) {
		t.Fatalf("failures = %d, want %d", b.Failures(), len(caps))
	}
}

func TestBackoffReset(t *testing.T) {
	b := NewBackoff(time.Second, time.Minute, 2.0, rand.New(rand.NewSource(1)))
	b.Next()
	b.Next()
	if b.Failures() != 2 {
		t.Fatalf("failures = %d, want 2", b.Failures())
	}
	b.Reset()
	if b.Failures() != 0 {
		t.Fatalf("after reset failures = %d, want 0", b.Failures())
	}
	// First delay after reset is bounded by base again (cap == base => exactly base).
	if d := b.Next(); d != time.Second {
		t.Fatalf("first post-reset delay = %v, want exactly base (1s)", d)
	}
}

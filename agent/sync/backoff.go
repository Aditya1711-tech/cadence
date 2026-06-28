package cloudsync

import (
	"math/rand"
	"time"
)

// Backoff computes exponential backoff with full jitter for transient sync
// failures (5xx / 429 / network). It is reset to base on success. The jitter
// source is injectable so tests are deterministic.
type Backoff struct {
	Base   time.Duration // first delay (typically the sync interval)
	Max    time.Duration // ceiling
	Factor float64       // growth per consecutive failure (e.g. 2.0)

	rng      *rand.Rand
	failures int
}

// NewBackoff builds a Backoff. A nil rng uses a fixed-seed source (callers that
// want process-unique jitter can pass their own seeded *rand.Rand).
func NewBackoff(base, max time.Duration, factor float64, rng *rand.Rand) *Backoff {
	if factor < 1 {
		factor = 2
	}
	if rng == nil {
		rng = rand.New(rand.NewSource(1))
	}
	return &Backoff{Base: base, Max: max, Factor: factor, rng: rng}
}

// Reset clears the failure count (call after a fully successful cycle).
func (b *Backoff) Reset() { b.failures = 0 }

// Failures returns the current consecutive-failure count.
func (b *Backoff) Failures() int { return b.failures }

// Next records one more failure and returns the next delay: a uniform random
// value in [Base, cap] where cap = min(Base * Factor^(failures-1), Max). Full
// jitter spreads retries so a recovering backend is not stampeded.
func (b *Backoff) Next() time.Duration {
	b.failures++
	cap := float64(b.Base)
	for i := 1; i < b.failures; i++ {
		cap *= b.Factor
		if cap >= float64(b.Max) {
			cap = float64(b.Max)
			break
		}
	}
	if cap < float64(b.Base) {
		cap = float64(b.Base)
	}
	span := cap - float64(b.Base)
	delay := float64(b.Base) + b.rng.Float64()*span
	return time.Duration(delay)
}

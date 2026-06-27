// Package collector is the OS activity source: it polls the active window and
// user idle time, segments them into Event Contract events, and feeds them to a
// sink (in production, the daemon's own /events route). The loop is
// OS-agnostic and unit-tested with fakes; the per-OS Watcher/IdleSource backends
// live in build-tagged platform_*.go files.
//
// Segmentation (see agent/docs/exploration/P1-A.2): one event spans a stretch of
// the same window and idle-state. On a window change the boundary is "now"; on
// an idle<->active transition the boundary is back-dated to when input actually
// stopped/resumed (now - idleSeconds), so silent gaps aren't credited as active
// work. Meetings (matched by app/title) get a longer idle ceiling so an engaged
// but quiet participant isn't marked idle.
package collector

import (
	"context"
	"io"
	"log/slog"
	"regexp"
	"time"

	"github.com/Aditya1711-tech/cadence/agent/internal/event"
)

// Window is the active foreground window as the OS backend sees it.
type Window struct {
	App      string // application name; "" if unknown
	Title    string // window title; "" if unavailable/not permitted
	HasTitle bool   // false => title intentionally absent (no permission / Wayland)
}

// Watcher reports the current foreground window.
type Watcher interface {
	Active() (Window, error)
}

// IdleSource reports seconds since the last user input.
type IdleSource interface {
	IdleSeconds() (float64, error)
}

// Sink receives a batch of events. It must not retain the slice.
type Sink func([]event.Event) error

// Config tunes the collector. Zero values fall back to sensible defaults.
type Config struct {
	MemberID       string
	Poll           time.Duration // sampling cadence (default 5s)
	IdleThreshold  time.Duration // no-input duration => idle (default 5m)
	MeetingCeiling time.Duration // idle threshold while in a meeting (default 30m)
	MeetingMatch   *regexp.Regexp
	Now            func() time.Time
	Logger         *slog.Logger
}

// defaultMeeting matches common meeting apps/titles for idle suppression.
var defaultMeeting = regexp.MustCompile(`(?i)\bzoom\b|microsoft teams|webex|google meet|meet\.google|teams\.microsoft|around\.co`)

// Collector runs the sampling loop.
type Collector struct {
	cfg     Config
	watcher Watcher
	idle    IdleSource
	sink    Sink
	cur     *segment
	pending []event.Event
}

type segment struct {
	app, title string
	hasTitle   bool
	isIdle     bool
	start      time.Time
}

// New builds a Collector, applying defaults for any zero Config fields.
func New(w Watcher, idle IdleSource, sink Sink, cfg Config) *Collector {
	if cfg.Poll <= 0 {
		cfg.Poll = 5 * time.Second
	}
	if cfg.IdleThreshold <= 0 {
		cfg.IdleThreshold = 5 * time.Minute
	}
	if cfg.MeetingCeiling <= 0 {
		cfg.MeetingCeiling = 30 * time.Minute
	}
	if cfg.MeetingMatch == nil {
		cfg.MeetingMatch = defaultMeeting
	}
	if cfg.Now == nil {
		cfg.Now = time.Now
	}
	if cfg.Logger == nil {
		cfg.Logger = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	if cfg.MemberID == "" {
		cfg.MemberID = "unknown"
	}
	return &Collector{cfg: cfg, watcher: w, idle: idle, sink: sink}
}

// Run samples on the configured cadence until ctx is cancelled, then flushes the
// open segment. It always returns nil (cancellation is normal shutdown).
func (c *Collector) Run(ctx context.Context) error {
	t := time.NewTicker(c.cfg.Poll)
	defer t.Stop()
	c.Sample(c.cfg.Now())
	for {
		select {
		case <-ctx.Done():
			c.Close(c.cfg.Now())
			return nil
		case <-t.C:
			c.Sample(c.cfg.Now())
		}
	}
}

// Sample takes one reading at time now, emitting an event when the window or
// idle-state changes. Exported for deterministic testing.
func (c *Collector) Sample(now time.Time) {
	w, err := c.watcher.Active()
	if err != nil {
		c.cfg.Logger.Warn("active window read failed", "err", err)
		c.flush()
		return
	}
	secs := 0.0
	if c.idle != nil {
		if s, err := c.idle.IdleSeconds(); err == nil {
			secs = s
		} else {
			c.cfg.Logger.Warn("idle read failed", "err", err)
		}
	}
	isIdle := c.computeIdle(w, secs)

	if c.cur == nil {
		c.cur = &segment{app: w.App, title: w.Title, hasTitle: w.HasTitle, isIdle: isIdle, start: now}
		return
	}

	windowChanged := c.cur.app != w.App || c.cur.title != w.Title || c.cur.hasTitle != w.HasTitle
	idleChanged := c.cur.isIdle != isIdle
	if !windowChanged && !idleChanged {
		c.flush()
		return
	}

	boundary := now
	if !windowChanged && idleChanged {
		// Back-date the boundary to when input actually stopped/resumed.
		boundary = now.Add(-time.Duration(secs * float64(time.Second)))
		if boundary.Before(c.cur.start) {
			boundary = c.cur.start
		}
		if boundary.After(now) {
			boundary = now
		}
	}
	c.emit(c.cur, boundary)
	c.cur = &segment{app: w.App, title: w.Title, hasTitle: w.HasTitle, isIdle: isIdle, start: boundary}
	c.flush()
}

// Close emits the open segment ending at now and flushes.
func (c *Collector) Close(now time.Time) {
	if c.cur != nil {
		c.emit(c.cur, now)
		c.cur = nil
	}
	c.flush()
}

func (c *Collector) computeIdle(w Window, secs float64) bool {
	threshold := c.cfg.IdleThreshold
	if c.cfg.MeetingMatch.MatchString(w.App) || (w.HasTitle && c.cfg.MeetingMatch.MatchString(w.Title)) {
		threshold = c.cfg.MeetingCeiling
	}
	return time.Duration(secs*float64(time.Second)) >= threshold
}

func (c *Collector) emit(seg *segment, end time.Time) {
	if end.Before(seg.start) {
		end = seg.start
	}
	app := seg.app
	if app == "" {
		app = "Unknown"
	}
	e, err := event.New(event.SourceOS, c.cfg.MemberID, seg.start, end, app)
	if err != nil {
		c.cfg.Logger.Error("build event failed", "err", err)
		return
	}
	if seg.hasTitle {
		e.Title = event.Ptr(seg.title)
	}
	e.IsIdle = seg.isIdle
	c.pending = append(c.pending, *e)
}

func (c *Collector) flush() {
	if len(c.pending) == 0 {
		return
	}
	if err := c.sink(c.pending); err != nil {
		c.cfg.Logger.Warn("sink failed; buffering for retry", "err", err, "buffered", len(c.pending))
		return
	}
	c.pending = nil
}

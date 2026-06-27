# P1-A.2 — Idle detection approach (findings)

Goal: define how the daemon decides a window of activity is **idle**
(`is_idle` in the Event Contract) — the input signal per OS, the threshold, the
polling cadence, and crucially how to **avoid mislabelling meetings** (little
input, but the user is engaged) as idle.

> Verified against current OS APIs.

---

## Summary recommendation

- **Poll OS-native "seconds since last input" every 5s.** All three OSes expose a
  cheap, permission-free counter of time since the last keyboard/mouse/HID event.
  We never log keystrokes — only the *elapsed-since-last-input* number.
- **Idle threshold = 300s (5 min) of no input, user-configurable.** Lower (e.g.
  60s) over-fragments normal reading/thinking time into "idle." 5 minutes is the
  common analytics convention and matches how RescueTime/WakaTime treat AFK.
- **Meeting-aware suppression.** If the foreground app/url matches the meeting
  allowlist, do **not** mark idle on input silence — instead require a longer
  ceiling (e.g. 30 min) before considering it idle. Optionally tighten later with
  camera/mic-in-use detection.
- Idle is a **segment boundary**, not just a flag: on active→idle and idle→active
  transitions we **close the current event and open a new one**, so durations
  stay honest and the dashboard's "focus vs fragmentation" score is meaningful.

---

## Per-OS input signal (all permission-free)

| OS | API | Returns | Permission |
|---|---|---|---|
| macOS | `CGEventSourceSecondsSinceLastEventType(kCGEventSourceStateHIDSystemState, kCGAnyInputEventType)` | seconds since last HID input (`CFTimeInterval`) | **none** |
| Linux X11 | XScreenSaver ext `XScreenSaverQueryInfo` → `XScreenSaverInfo.idle` | **milliseconds** idle | none (own session) |
| Linux Wayland | `ext-idle-notify-v1` (event-based) or `org.freedesktop.login1` `IdleHint`/`IdleSinceHint` | idle notifications / hint | none |
| Windows (deferred) | `GetLastInputInfo` → `GetTickCount() - dwTime` | ms since last input | none |

Notes:
- macOS path needs **no** `darwinkit`/CGO heavyweight — a ~10-line CGO call into
  CoreGraphics, or `purego`. This is the cheapest, most reliable idle signal.
- X11 is a simple poll. **Wayland is event-based** (`ext-idle-notify-v1` fires at
  a requested timeout) rather than pollable; we adapt the watcher so a Wayland
  backend reports idle via callback while X11/macOS poll. Where neither is
  reachable, fall back to `login1` `IdleHint`.

```go
// agent/internal/idle (proposed seam)
type Source interface {
    IdleSeconds() (float64, error) // poll model; Wayland backend may wrap an event stream
}
```

---

## State machine

```
ACTIVE ──(idleSeconds >= threshold)──▶ IDLE      // close active event, is_idle=false
   ▲                                     │         open idle event,  is_idle=true
   └──────(input resumes, idle < thr)────┘         close idle event, open active event
```

- **Cadence:** poll every **5s**. Cheap (one syscall), responsive enough; the
  threshold (not the poll) controls sensitivity.
- **Threshold:** `idle_threshold_sec` default **300**, user-configurable in the
  rules/config file. Micro-movements don't matter because we compare against a
  monotonic "seconds since last input," not deltas.
- **Hysteresis / debounce:** require the threshold to be exceeded on a poll to
  flip to idle, and *any* input (idleSeconds drops below threshold) to flip back.
  No flapping because the OS counter is monotonic between inputs.
- **Backdating idle start:** when we detect idle at poll time T with
  idleSeconds = S, the idle actually began at `T - S`. We set the closing active
  event's `ts_end` to `T - S` (not T) so we don't credit the silent gap as active
  work. The new idle event's `ts_start = T - S`.

---

## The meetings problem (don't punish engaged-but-quiet users)

A user in Zoom/Meet listening for 20 minutes generates almost no input but is
**not** idle. Pure input-silence would wrongly mark this idle and tank their
"meetings" time.

**v1 approach — meeting-aware suppression (no extra permission):**
1. Maintain a **meeting allowlist** (app + url patterns), shared with the
   classifier's `meetings` ruleset: `zoom.us`, `Zoom`, `meet.google.com`,
   `Microsoft Teams`, `teams.microsoft.com`, `Webex`, `slack huddle`, etc.
2. When the **foreground** matches the meeting allowlist, switch idle handling to
   a **relaxed ceiling**: don't mark idle on input silence; only mark idle after a
   long ceiling (default **30 min**) of silence *and* the meeting app no longer
   foreground — i.e. assume engagement during a meeting.
3. The event is then correctly categorized `meetings` with `is_idle=false`.

**Post-v1 hardening (note the seam, don't build yet):**
- **Camera/mic-in-use** detection as a stronger "engaged" signal:
  macOS has no clean public API (CoreMediaIO is private); on Linux we could check
  `/proc` for processes holding `/dev/video*` / PulseAudio/PipeWire source state.
  Treat as best-effort enhancement, off by default.
- **Audio output / fullscreen video** as a secondary engaged hint.

These are explicitly deferred; the allowlist suppression is enough for v1 and
matches the privacy-first stance (no device sensors probed without opt-in).

---

## Interaction with the Event Contract
- `is_idle` is set per event segment as above; `duration_ms` is recomputed from
  the (possibly backdated) `ts_start`/`ts_end` on close.
- Idle events still carry the foreground `app`/`title` at the moment idle began,
  so the timeline can show "idle while in VS Code" vs "idle while in Slack."
- The classifier may map long idle segments to category `idle`; meeting segments
  stay `meetings` even when quiet (see ruleset in P1-A.7).

---

## Open decisions to confirm before coding
1. Confirm **idle threshold = 300s** default (vs 60s). _Recommended: 300s._
2. Confirm **meeting allowlist suppression** with a 30-min ceiling for v1, with
   camera/mic detection explicitly deferred. _Recommended: yes._
3. Confirm **backdating** idle start to `T - idleSeconds` (honest active
   durations). _Recommended: yes._

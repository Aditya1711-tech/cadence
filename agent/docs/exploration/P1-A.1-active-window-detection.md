# P1-A.1 — Active-window detection per OS (findings)

Goal: identify, per OS, how the daemon learns the **foreground app name** and
**window title** that fill the Event Contract's `app` and `title` fields, the
**libraries/syscalls** involved, and the **permission prompts** each triggers.
Special focus: the least-intrusive macOS permission flow so users aren't scared
off at install.

> Verified against current (2024–2026) APIs and maintained Go libraries.

---

## Summary recommendation

- **Two fidelity tiers, app-name-first.** Always capture the foreground *app
  name* (and project, best-effort) because that needs **no permission** on every
  OS. Treat the *window title* as an **optional enhancement** the user explicitly
  enables, because every OS gates the title behind a permission.
- **Per-OS native code behind a small interface**, not a heavy cross-platform
  dependency. Define one Go interface and implement it per platform with build
  tags. This keeps the daemon lean and the permission story honest.

```go
// agent/internal/winwatch (proposed seam)
type ActiveWindow struct {
    App     string // always available, no permission
    Title   string // "" unless the user granted title permission
    PID     int
    HasTitle bool   // false => degrade gracefully, app-only event
}
type Watcher interface {
    Current() (ActiveWindow, error) // poll the foreground window
}
```

`title` is nullable in the Event Contract, so app-only events are fully valid.

---

## macOS

| What | API / syscall | Permission | Prompt location |
|---|---|---|---|
| Foreground **app name** + bundle id | `NSWorkspace.frontmostApplication` (`localizedName`, `bundleIdentifier`) | **none** | — |
| Window **title** via Accessibility | `AXUIElementCreateApplication` → `kAXFocusedWindowAttribute` → `kAXTitleAttribute` (`AXUIElementCopyAttributeValue`) | **Accessibility** (TCC) | System Settings → Privacy & Security → **Accessibility** |
| Window **title** via Quartz | `CGWindowListCopyWindowInfo` → `kCGWindowName` | **Screen Recording** (since macOS 10.15) | System Settings → Privacy & Security → **Screen Recording** |

Key facts:
- Getting the **app** is free and prompt-free — `NSWorkspace` gives the frontmost
  application with no TCC gate. This is our default tier.
- Both **title** paths cost a scary permission. Crucially, since **Catalina
  (10.15)** `CGWindowListCopyWindowInfo` **omits** `kCGWindowName` unless Screen
  Recording is granted — and "Screen Recording" is the most alarming permission
  for a trust-first product. **Avoid the Quartz title path.**
- The **Accessibility** path is the lesser evil: its prompt reads "control your
  computer," not "record your screen," and it does not imply pixels leaving the
  device. We gate it behind `AXIsProcessTrusted()` and only call
  `AXIsProcessTrustedWithOptions({kAXTrustedCheckOptionPrompt: true})` **after**
  the user opts into title capture in onboarding. Sandboxed/App-Store builds
  cannot use AX — we ship a Developer-ID-signed direct download, which is fine.

### Best-UX permission flow (macOS)
1. **Install → runs immediately, app-name tier, zero prompts.** User sees value
   (timeline by app/project) without granting anything.
2. Onboarding offers an optional **"Show window titles for richer detail"**
   toggle. Only when enabled do we trigger the **Accessibility** prompt (never
   Screen Recording), with one sentence explaining we read the title string
   locally and never capture pixels.
3. If denied/revoked, we silently degrade to app-only (`HasTitle=false`). No
   nagging.

### Go binding
- `github.com/progrium/darwinkit` (the renamed, maintained `macdriver`) provides
  AppKit/Quartz bindings — **CGO required**. For idle we won't even need it (see
  P1-A.2). For the title path a tiny hand-rolled CGO/Obj-C shim is also viable;
  `purego` is a CGO-free fallback if we want to avoid a C compiler.

---

## Linux — X11

| What | API | Permission |
|---|---|---|
| Active window | EWMH `_NET_ACTIVE_WINDOW` on the root window | none (same X session) |
| Title | `_NET_WM_NAME` (UTF-8, preferred) → fallback legacy `WM_NAME` | none |

- Maintained Go libs: **`github.com/jezek/xgb`** (low-level protocol) +
  **`github.com/jezek/xgbutil`** (EWMH/ICCCM helpers: `ewmh.ActiveWindowGet`,
  `ewmh.WmNameGet`). These are the live forks of the stale `BurntSushi/*`.
- No special permission inside the user's own X session; we connect to `$DISPLAY`.
- App name: derive from `WM_CLASS` / the window's PID (`_NET_WM_PID` → `/proc`).

## Linux — Wayland (the hard seam)

There is **no standard cross-compositor protocol that exposes the active-window
title** — this is a deliberate security isolation choice. Options are
compositor-specific:

- **GNOME/Mutter:** `org.gnome.Shell.Eval` (D-Bus) can read the focused window,
  but since GNOME 41 it is behind `unsafe-mode` and **disabled by default** →
  not usable for us.
- **KDE/KWin:** D-Bus scripting (`org.kde.KWin` / `loadScript`).
- **wlroots (sway/Hyprland):** `wlr-foreign-toplevel-management-unstable-v1`
  lists toplevels with titles + activated state; sway also exposes **i3 IPC
  `get_tree`**. (`ext-foreign-toplevel-list-v1` lists toplevels but intentionally
  omits which is active.)

**Decision:** On Wayland v1 ships **app-name-only** detection (from the toplevel
list where available, else `/proc` of focused PID via the compositor IPC we can
reach), and titles are simply unavailable → `HasTitle=false`. We detect the
session type via `$XDG_SESSION_TYPE` / `$WAYLAND_DISPLAY` and pick X11 vs Wayland
at runtime. Document Wayland title capture as a post-v1 enhancement.

---

## Windows (deferred — note the seam)

Not in v1 scope, but reserve the interface so it slots in cleanly:
- Active window/title: `GetForegroundWindow` + `GetWindowTextW` (user32.dll).
- **No CGO** — reachable via `golang.org/x/sys/windows`
  (`NewLazySystemDLL("user32.dll")` + `NewProc`). Implement in a build-tagged
  `winwatch_windows.go` later; the `Watcher` interface above already fits.

---

## Why not a single cross-platform library (robotgo)?

`github.com/go-vgo/robotgo` covers active window + idle on all three OSes in one
API, but: historically **heavy CGO** (needs GCC + X11 dev headers on Linux),
painful cross-compilation, and it drags in a large GUI-automation surface a
daemon doesn't need. Recent versions add some CGO-free backends behind build
tags, but the default macOS/X11 paths still need CGO. For a lean, auditable,
trust-first daemon, **per-OS native code behind our own interface wins.**

---

## Open decisions to confirm before coding
1. Confirm **app-name-first, title-opt-in** as the product stance (drives the
   whole permission UX). _Recommended: yes._
2. Confirm Wayland ships **app-only** in v1 (titles deferred). _Recommended: yes._
3. Binding choice for the macOS title path: `darwinkit` (CGO) vs a tiny CGO shim
   vs `purego`. Can defer until P1-A.6 since the app-only tier needs none of them.

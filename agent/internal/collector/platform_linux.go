//go:build linux

package collector

import "errors"

// Linux backend — scaffold. To be implemented and runtime-verified on Linux.
// Intended approach, per agent/docs/exploration/P1-A.1 and P1-A.2:
//
//	X11 (detect via $XDG_SESSION_TYPE / $DISPLAY):
//	  - Active window: EWMH _NET_ACTIVE_WINDOW on the root window, then
//	    _NET_WM_NAME (UTF-8) / WM_NAME for the title; WM_CLASS / _NET_WM_PID
//	    for the app. Use github.com/jezek/xgb + github.com/jezek/xgbutil.
//	  - Idle: XScreenSaver extension XScreenSaverQueryInfo (idle in ms).
//
//	Wayland (no standard title protocol — app-only):
//	  - ext-idle-notify-v1 for idle; wlr-foreign-toplevel-management or
//	    compositor IPC (sway i3-IPC) for the app where available;
//	    HasTitle=false.
func NewPlatform() (Watcher, IdleSource, error) {
	return nil, nil, errors.New("collector: Linux backend not yet implemented (X11 EWMH + XScreenSaver / Wayland app-only)")
}

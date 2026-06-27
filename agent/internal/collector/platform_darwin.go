//go:build darwin

package collector

import "errors"

// macOS backend — scaffold. To be implemented and runtime-verified on a Mac
// (this stream is developed on Windows). Intended approach, per
// agent/docs/exploration/P1-A.1 and P1-A.2:
//
//   - App name: NSWorkspace.frontmostApplication (localizedName) — no permission.
//   - Window title: Accessibility API (AXUIElementCopyAttributeValue with
//     kAXTitleAttribute), requested opt-in; NEVER the Screen-Recording-gated
//     CGWindowListCopyWindowInfo path. Degrade to HasTitle=false when not granted.
//   - Idle: CGEventSourceSecondsSinceLastEventType(kCGEventSourceStateHIDSystemState,
//     kCGAnyInputEventType) — no permission.
//
// Bind via github.com/progrium/darwinkit (CGO) or purego.
func NewPlatform() (Watcher, IdleSource, error) {
	return nil, nil, errors.New("collector: macOS backend not yet implemented (NSWorkspace + Accessibility + CGEventSource)")
}

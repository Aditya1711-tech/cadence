//go:build windows

package collector

import (
	"fmt"
	"path/filepath"
	"strings"
	"unsafe"

	"golang.org/x/sys/windows"
)

// Windows active-window + idle backend via user32/kernel32. No CGO.

var (
	user32   = windows.NewLazySystemDLL("user32.dll")
	kernel32 = windows.NewLazySystemDLL("kernel32.dll")

	procGetForegroundWindow      = user32.NewProc("GetForegroundWindow")
	procGetWindowTextW           = user32.NewProc("GetWindowTextW")
	procGetWindowTextLengthW     = user32.NewProc("GetWindowTextLengthW")
	procGetWindowThreadProcessId = user32.NewProc("GetWindowThreadProcessId")
	procGetLastInputInfo         = user32.NewProc("GetLastInputInfo")
	procGetTickCount             = kernel32.NewProc("GetTickCount")
)

type winWatcher struct{}

func (winWatcher) Active() (Window, error) {
	hwnd, _, _ := procGetForegroundWindow.Call()
	if hwnd == 0 {
		return Window{App: "Unknown"}, nil
	}

	var title string
	if n, _, _ := procGetWindowTextLengthW.Call(hwnd); n > 0 {
		buf := make([]uint16, int(n)+1)
		procGetWindowTextW.Call(hwnd, uintptr(unsafe.Pointer(&buf[0])), uintptr(len(buf)))
		title = windows.UTF16ToString(buf)
	}

	var pid uint32
	procGetWindowThreadProcessId.Call(hwnd, uintptr(unsafe.Pointer(&pid)))

	return Window{App: appNameFromPID(pid), Title: title, HasTitle: title != ""}, nil
}

func appNameFromPID(pid uint32) string {
	if pid == 0 {
		return "Unknown"
	}
	h, err := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION, false, pid)
	if err != nil {
		return "Unknown"
	}
	defer windows.CloseHandle(h)

	buf := make([]uint16, windows.MAX_PATH)
	size := uint32(len(buf))
	if err := windows.QueryFullProcessImageName(h, 0, &buf[0], &size); err != nil {
		return "Unknown"
	}
	base := filepath.Base(windows.UTF16ToString(buf[:size]))
	return strings.TrimSuffix(base, filepath.Ext(base)) // "Code.exe" -> "Code"
}

type lastInputInfo struct {
	cbSize uint32
	dwTime uint32
}

type winIdle struct{}

func (winIdle) IdleSeconds() (float64, error) {
	lii := lastInputInfo{cbSize: uint32(unsafe.Sizeof(lastInputInfo{}))}
	r, _, err := procGetLastInputInfo.Call(uintptr(unsafe.Pointer(&lii)))
	if r == 0 {
		return 0, fmt.Errorf("GetLastInputInfo failed: %v", err)
	}
	tick, _, _ := procGetTickCount.Call()
	idleMs := uint32(tick) - lii.dwTime // uint32 subtraction handles 49.7-day wrap
	return float64(idleMs) / 1000.0, nil
}

// NewPlatform returns the Windows Watcher and IdleSource.
func NewPlatform() (Watcher, IdleSource, error) {
	return winWatcher{}, winIdle{}, nil
}

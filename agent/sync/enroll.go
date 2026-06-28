package cloudsync

import (
	"context"
	"fmt"
	"strings"
)

// Enroll redeems a one-time device code: it exchanges the code for the canonical
// member_id + token pair and persists them in the keychain. After this the sync
// loop (which reloads credentials each cycle) begins shipping events. Safe to
// re-run to re-enroll a device.
//
// The code may be pasted as a bare code or as an enrollment URL/blob containing
// it; trimCode normalizes common shapes.
func Enroll(ctx context.Context, client *Client, keys *Keystore, rawCode string) (Creds, error) {
	code := trimCode(rawCode)
	if code == "" {
		return Creds{}, fmt.Errorf("cloudsync: empty enrollment code")
	}
	resp, err := client.Enroll(ctx, code)
	if err != nil {
		if isStatus(err, 400, 404, 410) {
			return Creds{}, fmt.Errorf("cloudsync: enrollment code invalid or expired — mint a new one in the web app: %w", err)
		}
		return Creds{}, err
	}
	creds := Creds{
		MemberID:     resp.MemberID,
		AccessToken:  resp.AccessToken,
		RefreshToken: resp.RefreshToken,
	}
	if creds.RefreshToken == "" || creds.MemberID == "" {
		return Creds{}, fmt.Errorf("cloudsync: enrollment response missing member_id or refresh token")
	}
	if err := keys.SaveEnrollment(creds); err != nil {
		return Creds{}, err
	}
	return creds, nil
}

// Status is a snapshot for the `cadence-agent status` command.
type Status struct {
	Enrolled   bool
	MemberID   string // canonical, may be "" when not enrolled
	SyncedRows int    // dedupe rows currently tracked
}

// Snapshot reports current enrollment + sync state. state may be nil (no sidecar
// opened), in which case SyncedRows is 0.
func Snapshot(keys *Keystore, state *State) (Status, error) {
	st := Status{Enrolled: keys.Enrolled(), MemberID: keys.MemberID()}
	if state != nil {
		n, err := state.Count()
		if err != nil {
			return st, err
		}
		st.SyncedRows = n
	}
	return st, nil
}

// trimCode normalizes a pasted code: strips whitespace and, if a URL/blob was
// pasted, takes the last path/query segment that looks like the code.
func trimCode(raw string) string {
	c := strings.TrimSpace(raw)
	if c == "" {
		return ""
	}
	// If pasted as a URL like https://app/enroll?code=XYZ or .../enroll/XYZ
	if i := strings.LastIndex(c, "code="); i >= 0 {
		c = c[i+len("code="):]
		if amp := strings.IndexByte(c, '&'); amp >= 0 {
			c = c[:amp]
		}
	} else if strings.Contains(c, "/") {
		parts := strings.Split(strings.TrimRight(c, "/"), "/")
		c = parts[len(parts)-1]
	}
	return strings.TrimSpace(c)
}

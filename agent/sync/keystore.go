package cloudsync

import (
	"errors"
	"fmt"

	"github.com/Aditya1711-tech/cadence/agent/internal/keyring"
)

// Keychain account names under the agent's keychain service. The store master
// key (P1-A) and member-id already live under this service; we add the cloud
// auth tokens beside them. Secrets never touch disk in plaintext.
const (
	acctMemberID = "member-id"     // canonical member_id, adopted at enrollment
	acctAccess   = "cloud-access"  // current access JWT (~60m)
	acctRefresh  = "cloud-refresh" // current opaque refresh token (rotating)
)

// ErrNotEnrolled is returned when cloud credentials are absent — the daemon
// runs fully locally until a device code is redeemed.
var ErrNotEnrolled = errors.New("cloudsync: device not enrolled")

// Creds is the daemon's cloud identity + tokens.
type Creds struct {
	MemberID     string
	AccessToken  string
	RefreshToken string
}

// Keystore persists cloud credentials in the OS keychain via the shared
// keyring.Keyring (OS keychain in prod, in-memory fake in tests).
type Keystore struct {
	kr      keyring.Keyring
	service string
}

// NewKeystore binds a Keystore to a keyring and service name (the agent's
// CADENCE_KEYCHAIN_SERVICE, default com.cadence.agent).
func NewKeystore(kr keyring.Keyring, service string) *Keystore {
	return &Keystore{kr: kr, service: service}
}

// Enrolled reports whether a refresh token is present (the durable proof of
// enrollment; the access token may have expired).
func (k *Keystore) Enrolled() bool {
	v, err := k.kr.Get(k.service, acctRefresh)
	return err == nil && v != ""
}

// Load returns the stored credentials, or ErrNotEnrolled if none exist.
func (k *Keystore) Load() (Creds, error) {
	refresh, err := k.get(acctRefresh)
	if err != nil {
		if errors.Is(err, keyring.ErrNotFound) {
			return Creds{}, ErrNotEnrolled
		}
		return Creds{}, err
	}
	member, err := k.get(acctMemberID)
	if err != nil && !errors.Is(err, keyring.ErrNotFound) {
		return Creds{}, err
	}
	access, err := k.get(acctAccess)
	if err != nil && !errors.Is(err, keyring.ErrNotFound) {
		return Creds{}, err
	}
	return Creds{MemberID: member, AccessToken: access, RefreshToken: refresh}, nil
}

// SaveEnrollment persists the full identity + token pair after a successful
// device enroll. It OVERWRITES any prior identity (re-enroll is supported;
// ingest idempotency makes events synced under an old identity harmless).
func (k *Keystore) SaveEnrollment(c Creds) error {
	if err := k.set(acctMemberID, c.MemberID); err != nil {
		return err
	}
	return k.SaveTokens(c.AccessToken, c.RefreshToken)
}

// SaveTokens persists a rotated token pair (after enroll or refresh). The
// refresh token is single-use server-side, so it must be persisted before the
// next use; callers serialize refresh to avoid a reuse that revokes the family.
func (k *Keystore) SaveTokens(access, refresh string) error {
	if err := k.set(acctAccess, access); err != nil {
		return err
	}
	if err := k.set(acctRefresh, refresh); err != nil {
		return err
	}
	return nil
}

// MemberID returns the adopted canonical member_id, or "" if not enrolled.
func (k *Keystore) MemberID() string {
	v, _ := k.get(acctMemberID)
	return v
}

func (k *Keystore) get(account string) (string, error) {
	return k.kr.Get(k.service, account)
}

func (k *Keystore) set(account, secret string) error {
	if err := k.kr.Set(k.service, account, secret); err != nil {
		return fmt.Errorf("cloudsync: persist %s: %w", account, err)
	}
	return nil
}

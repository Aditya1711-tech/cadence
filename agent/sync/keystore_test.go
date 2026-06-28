package cloudsync

import (
	"errors"
	"testing"

	"github.com/Aditya1711-tech/cadence/agent/internal/keyring"
)

func TestKeystoreNotEnrolled(t *testing.T) {
	k := NewKeystore(keyring.NewMemory(), "svc")
	if k.Enrolled() {
		t.Fatal("fresh keystore reports enrolled")
	}
	if _, err := k.Load(); !errors.Is(err, ErrNotEnrolled) {
		t.Fatalf("Load on empty = %v, want ErrNotEnrolled", err)
	}
	if k.MemberID() != "" {
		t.Fatalf("MemberID = %q, want empty", k.MemberID())
	}
}

func TestKeystoreSaveLoadAndRotate(t *testing.T) {
	k := NewKeystore(keyring.NewMemory(), "svc")
	if err := k.SaveEnrollment(Creds{MemberID: "m-1", AccessToken: "a1", RefreshToken: "r1"}); err != nil {
		t.Fatalf("SaveEnrollment: %v", err)
	}
	if !k.Enrolled() {
		t.Fatal("after SaveEnrollment, not enrolled")
	}
	c, err := k.Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if c.MemberID != "m-1" || c.AccessToken != "a1" || c.RefreshToken != "r1" {
		t.Fatalf("loaded creds = %+v", c)
	}

	// Rotation persists the new pair, member_id unchanged.
	if err := k.SaveTokens("a2", "r2"); err != nil {
		t.Fatalf("SaveTokens: %v", err)
	}
	c, _ = k.Load()
	if c.AccessToken != "a2" || c.RefreshToken != "r2" || c.MemberID != "m-1" {
		t.Fatalf("after rotate creds = %+v", c)
	}
}

package cloudsync

import (
	"context"
	"errors"
	"testing"
	"time"
)

func TestClientEnroll(t *testing.T) {
	b := newFakeBackend(t)
	b.set(func(b *fakeBackend) {
		b.enrollResp = &EnrollResponse{MemberID: "m-9", AccessToken: "acc", RefreshToken: "ref", TokenType: "Bearer", ExpiresInSeconds: 3600}
	})
	c := NewClient(b.base(), nil)

	resp, err := c.Enroll(context.Background(), "CODE123")
	if err != nil {
		t.Fatalf("Enroll: %v", err)
	}
	if resp.MemberID != "m-9" || resp.AccessToken != "acc" || resp.RefreshToken != "ref" {
		t.Fatalf("enroll resp = %+v", resp)
	}
}

func TestClientRefresh(t *testing.T) {
	b := newFakeBackend(t)
	c := NewClient(b.base(), nil)
	tp, err := c.Refresh(context.Background(), "r1")
	if err != nil {
		t.Fatalf("Refresh: %v", err)
	}
	if tp.AccessToken != "a2" || tp.RefreshToken != "r2" {
		t.Fatalf("refresh tp = %+v", tp)
	}
}

func TestClientIngestHappy(t *testing.T) {
	b := newFakeBackend(t)
	c := NewClient(b.base(), nil)
	evs := events2(t)

	res, err := c.Ingest(context.Background(), "acc", evs)
	if err != nil {
		t.Fatalf("Ingest: %v", err)
	}
	if res.Received != len(evs) || res.Stored != len(evs) || res.Duplicates != 0 {
		t.Fatalf("ingest result = %+v", res)
	}
}

func TestClientIngestUnauthorized(t *testing.T) {
	b := newFakeBackend(t)
	b.set(func(b *fakeBackend) { b.acceptAccess = "good" })
	c := NewClient(b.base(), nil)

	_, err := c.Ingest(context.Background(), "bad", events2(t))
	if !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("ingest with bad token = %v, want ErrUnauthorized", err)
	}
}

func TestClientAPIErrorParsesProblemJSON(t *testing.T) {
	b := newFakeBackend(t)
	b.set(func(b *fakeBackend) { b.failIngest = true })
	c := NewClient(b.base(), nil)

	_, err := c.Ingest(context.Background(), "acc", events2(t))
	var ae *APIError
	if !errors.As(err, &ae) {
		t.Fatalf("err = %v, want *APIError", err)
	}
	if ae.Status != 503 || ae.Detail != "service unavailable" {
		t.Fatalf("APIError = %+v", ae)
	}
}

func TestClientContextTimeout(t *testing.T) {
	b := newFakeBackend(t)
	c := NewClient(b.base(), nil)
	ctx, cancel := context.WithTimeout(context.Background(), time.Nanosecond)
	defer cancel()
	if _, err := c.Refresh(ctx, "r1"); err == nil {
		t.Fatal("expected context error, got nil")
	}
}

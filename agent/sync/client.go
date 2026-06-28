package cloudsync

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/Aditya1711-tech/cadence/agent/internal/event"
)

// API paths on the backend (docs/00-SYSTEM-KNOWLEDGE.md §6, P2-A as-built).
const (
	pathEnroll  = "/api/v1/auth/device/enroll"
	pathRefresh = "/api/v1/auth/refresh"
	pathIngest  = "/api/v1/ingest/events"
)

// ErrUnauthorized is returned on a 401 — the caller should refresh and retry.
var ErrUnauthorized = errors.New("cloudsync: unauthorized (401)")

// APIError is a non-2xx response carrying the parsed problem+json detail (§6).
type APIError struct {
	Status int
	Title  string
	Detail string
}

func (e *APIError) Error() string {
	if e.Detail != "" {
		return fmt.Sprintf("cloudsync: api %d: %s — %s", e.Status, e.Title, e.Detail)
	}
	return fmt.Sprintf("cloudsync: api %d: %s", e.Status, e.Title)
}

// Client talks to the Cadence backend. It is safe for serialized use by the
// single sync goroutine.
type Client struct {
	base string
	http *http.Client
}

// NewClient builds a Client for the given backend base URL (CADENCE_CLOUD_BASE).
// A nil httpClient gets a sensible default with a request timeout.
func NewClient(base string, httpClient *http.Client) *Client {
	if httpClient == nil {
		httpClient = &http.Client{Timeout: 30 * time.Second}
	}
	return &Client{base: strings.TrimRight(base, "/"), http: httpClient}
}

// TokenPair is the access+refresh pair returned by enroll and refresh. Wire is
// snake_case (global Jackson naming on the backend).
type TokenPair struct {
	AccessToken      string `json:"access_token"`
	RefreshToken     string `json:"refresh_token"`
	TokenType        string `json:"token_type"`
	ExpiresInSeconds int64  `json:"expires_in_seconds"`
}

// EnrollResponse adds the canonical member_id to the token pair.
type EnrollResponse struct {
	MemberID         string `json:"member_id"`
	AccessToken      string `json:"access_token"`
	RefreshToken     string `json:"refresh_token"`
	TokenType        string `json:"token_type"`
	ExpiresInSeconds int64  `json:"expires_in_seconds"`
}

// IngestResult mirrors the backend response { received, stored, duplicates }.
type IngestResult struct {
	Received   int `json:"received"`
	Stored     int `json:"stored"`
	Duplicates int `json:"duplicates"`
}

// Enroll redeems a one-time device code for the daemon's identity + tokens.
func (c *Client) Enroll(ctx context.Context, code string) (EnrollResponse, error) {
	var out EnrollResponse
	err := c.do(ctx, http.MethodPost, pathEnroll, "", map[string]string{"code": code}, &out)
	return out, err
}

// Refresh rotates the token pair. The returned refresh token must be persisted
// before any further use (server-side single-use; reuse revokes the family).
func (c *Client) Refresh(ctx context.Context, refreshToken string) (TokenPair, error) {
	var out TokenPair
	err := c.do(ctx, http.MethodPost, pathRefresh, "", map[string]string{"refresh_token": refreshToken}, &out)
	return out, err
}

// Ingest POSTs a batch of events (≤1000, caller-enforced) with a Bearer access
// token. A 401 surfaces as ErrUnauthorized so the loop can refresh and retry.
func (c *Client) Ingest(ctx context.Context, accessToken string, events []event.Event) (IngestResult, error) {
	var out IngestResult
	err := c.do(ctx, http.MethodPost, pathIngest, accessToken, events, &out)
	return out, err
}

// do executes one JSON request/response. On 401 it returns ErrUnauthorized; on
// any other non-2xx it returns an *APIError with the problem+json detail.
func (c *Client) do(ctx context.Context, method, path, bearer string, body, out any) error {
	buf, err := json.Marshal(body)
	if err != nil {
		return fmt.Errorf("cloudsync: marshal request: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.base+path, bytes.NewReader(buf))
	if err != nil {
		return fmt.Errorf("cloudsync: build request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	if bearer != "" {
		req.Header.Set("Authorization", "Bearer "+bearer)
	}

	resp, err := c.http.Do(req)
	if err != nil {
		return fmt.Errorf("cloudsync: %s %s: %w", method, path, err)
	}
	defer resp.Body.Close()
	respBody, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))

	if resp.StatusCode == http.StatusUnauthorized {
		return ErrUnauthorized
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return apiErrorFrom(resp.StatusCode, respBody)
	}
	if out != nil && len(bytes.TrimSpace(respBody)) > 0 {
		if err := json.Unmarshal(respBody, out); err != nil {
			return fmt.Errorf("cloudsync: decode response: %w", err)
		}
	}
	return nil
}

// apiErrorFrom parses an RFC 7807 problem+json body into an *APIError, falling
// back to the raw body when it is not JSON.
func apiErrorFrom(status int, body []byte) error {
	var p struct {
		Title  string `json:"title"`
		Detail string `json:"detail"`
	}
	if err := json.Unmarshal(body, &p); err == nil && (p.Title != "" || p.Detail != "") {
		return &APIError{Status: status, Title: p.Title, Detail: p.Detail}
	}
	return &APIError{Status: status, Title: http.StatusText(status), Detail: strings.TrimSpace(string(body))}
}

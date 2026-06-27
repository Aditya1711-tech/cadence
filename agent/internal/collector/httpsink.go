package collector

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/Aditya1711-tech/cadence/agent/internal/event"
)

// NewHTTPSink returns a Sink that POSTs event batches to the daemon's local
// /events route (baseURL like "http://127.0.0.1:47821"). A nil client gets a
// default with a short timeout.
func NewHTTPSink(baseURL string, client *http.Client) Sink {
	if client == nil {
		client = &http.Client{Timeout: 5 * time.Second}
	}
	url := strings.TrimRight(baseURL, "/") + "/events"
	return func(events []event.Event) error {
		body, err := json.Marshal(events)
		if err != nil {
			return fmt.Errorf("collector sink: marshal: %w", err)
		}
		req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(body))
		if err != nil {
			return fmt.Errorf("collector sink: request: %w", err)
		}
		req.Header.Set("Content-Type", "application/json")
		resp, err := client.Do(req)
		if err != nil {
			return fmt.Errorf("collector sink: post: %w", err)
		}
		defer resp.Body.Close()
		_, _ = io.Copy(io.Discard, resp.Body)
		if resp.StatusCode >= 300 {
			return fmt.Errorf("collector sink: unexpected status %d", resp.StatusCode)
		}
		return nil
	}
}

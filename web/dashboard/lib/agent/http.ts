// HttpAgentClient — talks to the real local daemon over loopback HTTP.
//
// Reconciled to the SHIPPED P1-A.5 contract (agent/internal/api/server.go):
//   GET /timeline?from&to  ->  a BARE JSON array of Event Contract objects.
//   - filtered by ts_start in [from, to) (RFC3339 UTC); defaults to last 24h
//   - no envelope, no pagination, no auth (loopback-only); errors are
//     RFC 7807 problem+json.
// Anything coupled to the wire shape lives in THIS file, so a future contract
// change stays contained.

import type { Event } from "@/lib/contract/event";
import { AgentOfflineError, type AgentClient } from "@/lib/agent/client";

export class HttpAgentClient implements AgentClient {
  constructor(private readonly baseUrl: string) {}

  async getTimeline(from: Date, to: Date): Promise<Event[]> {
    const url = new URL("/timeline", this.baseUrl);
    url.searchParams.set("from", from.toISOString());
    url.searchParams.set("to", to.toISOString());

    let res: Response;
    try {
      res = await fetch(url, {
        headers: { Accept: "application/json" },
        cache: "no-store",
      });
    } catch (err) {
      // Connection refused / DNS / timeout => daemon is offline.
      throw new AgentOfflineError(
        `cannot reach Cadence daemon at ${this.baseUrl}`,
        err,
      );
    }

    if (!res.ok) {
      // Surface the daemon's problem+json detail when present.
      let detail = `${res.status} ${res.statusText}`;
      try {
        const body = (await res.json()) as { detail?: string; title?: string };
        detail = body.detail ?? body.title ?? detail;
      } catch {
        // non-JSON error body; keep the status line
      }
      throw new Error(`timeline request failed: ${detail}`);
    }

    const body = (await res.json()) as Event[];
    return Array.isArray(body) ? body : [];
  }
}

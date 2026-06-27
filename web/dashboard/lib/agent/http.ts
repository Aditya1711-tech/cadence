// HttpAgentClient — talks to the real local daemon over loopback HTTP.
//
// ⚠️ PROVISIONAL: this targets the read contract PROPOSED in
// web/dashboard/docs/REQUIREMENTS-P1-D.md (P1-D.2), which P1-A has not yet
// frozen (P1-A.5 is still todo). Everything coupled to the wire shape lives in
// THIS file so the freeze costs a one-file change, not a UI rewrite.

import type { Event } from "@/lib/contract/event";
import { AgentOfflineError, type AgentClient } from "@/lib/agent/client";

/** Shape of `GET /timeline` as proposed in P1-D.2 (pending P1-A confirmation). */
interface TimelineResponse {
  events: Event[];
  next_cursor: string | null;
}

const MAX_PAGES = 100; // safety bound; a day is a few hundred events / a few pages

export class HttpAgentClient implements AgentClient {
  constructor(private readonly baseUrl: string) {}

  async getTimeline(from: Date, to: Date): Promise<Event[]> {
    const events: Event[] = [];
    let cursor: string | null = null;

    for (let page = 0; page < MAX_PAGES; page++) {
      const url = new URL("/timeline", this.baseUrl);
      url.searchParams.set("from", from.toISOString());
      url.searchParams.set("to", to.toISOString());
      if (cursor) url.searchParams.set("cursor", cursor);

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
        throw new Error(
          `timeline request failed: ${res.status} ${res.statusText}`,
        );
      }

      const body = (await res.json()) as TimelineResponse;
      events.push(...body.events);
      cursor = body.next_cursor;
      if (!cursor) break;
    }

    return events;
  }
}

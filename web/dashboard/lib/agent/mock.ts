// MockAgentClient — serves a synthetic but realistic day of events.
//
// Used in dev and for verifying the UI before the daemon's read route (P1-A.5)
// exists. Selected via env in lib/agent/index.ts. Returns only events that
// overlap the requested window, mirroring how the real route will behave.

import type { Event } from "@/lib/contract/event";
import type { AgentClient } from "@/lib/agent/client";
import { sampleDay } from "@/lib/fixtures/day";

export class MockAgentClient implements AgentClient {
  async getTimeline(from: Date, to: Date): Promise<Event[]> {
    const fromMs = from.getTime();
    const toMs = to.getTime();
    return sampleDay()
      .filter((e) => {
        const start = Date.parse(e.ts_start);
        const end = Date.parse(e.ts_end);
        // overlap test: event touches [from, to)
        return end > fromMs && start < toMs;
      })
      .sort((a, b) => Date.parse(a.ts_start) - Date.parse(b.ts_start));
  }
}

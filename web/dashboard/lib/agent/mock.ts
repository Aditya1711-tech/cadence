// MockAgentClient — serves a synthetic but realistic day of events.
//
// Used in dev and for UI verification without a running daemon. Selected via
// env in lib/agent/index.ts. Mirrors the shipped daemon semantics (P1-A.5):
// events with ts_start in [from, to), ordered by ts_start ascending.

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
        return start >= fromMs && start < toMs;
      })
      .sort((a, b) => Date.parse(a.ts_start) - Date.parse(b.ts_start));
  }
}

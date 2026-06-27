// Agent client interface — the seam between the dashboard and the local daemon.
//
// Phase 1 reality: P1-A.CONTRACT froze the EVENT shape, but the local read
// ROUTE (P1-A.5) and its timeline/summary response shapes are NOT frozen yet
// (see the NEEDS line in docs/PROGRESS.md). To avoid hard-blocking the UI, the
// dashboard depends on exactly ONE route — `GET /timeline` — which is already
// sketched in P1-A's "Variables to set", and computes every rollup itself
// (see lib/summary.ts). When P1-A.5 lands, only lib/agent/http.ts changes.

import type { Event } from "@/lib/contract/event";

/** A read-only view of the local activity store for a time window. */
export interface AgentClient {
  /**
   * Events overlapping [from, to). Implementations return them ordered by
   * ts_start ascending. `from`/`to` are absolute instants; the caller has
   * already resolved local-day boundaries to UTC.
   */
  getTimeline(from: Date, to: Date): Promise<Event[]>;
}

/**
 * Raised when the local daemon cannot be reached. The UI turns this into the
 * friendly "Cadence isn't running" state (P1-D.7) rather than a crash.
 */
export class AgentOfflineError extends Error {
  readonly cause?: unknown;
  constructor(message: string, cause?: unknown) {
    super(message);
    this.name = "AgentOfflineError";
    this.cause = cause;
  }
}

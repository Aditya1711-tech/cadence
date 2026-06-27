// Server-side loader: fetch a day's events from the local agent and roll them
// up. Returns a discriminated result so the page can render friendly offline /
// error / empty states (P1-D.7) instead of throwing.

import "server-only";
import { getAgentClient, AgentOfflineError } from "@/lib/agent";
import { computeSummary, type DaySummary } from "@/lib/summary";
import { startOfLocalDay, endOfLocalDay } from "@/lib/time";
import type { Event } from "@/lib/contract/event";

export type DayLoad =
  | { status: "ok"; from: string; to: string; events: Event[]; summary: DaySummary }
  | { status: "offline"; message: string }
  | { status: "error"; message: string };

/** Load + summarize the local day containing `date` (defaults to now). */
export async function loadDay(date = new Date()): Promise<DayLoad> {
  const from = startOfLocalDay(date);
  const to = endOfLocalDay(date);
  try {
    const events = await getAgentClient().getTimeline(from, to);
    return {
      status: "ok",
      from: from.toISOString(),
      to: to.toISOString(),
      events,
      summary: computeSummary(events),
    };
  } catch (err) {
    if (err instanceof AgentOfflineError) {
      return { status: "offline", message: err.message };
    }
    return {
      status: "error",
      message: err instanceof Error ? err.message : "unknown error",
    };
  }
}

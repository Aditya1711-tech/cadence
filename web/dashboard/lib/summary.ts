// Day summary — every rollup the dashboard shows, computed from raw events.
//
// The dashboard depends only on `GET /timeline`; everything glanceable is
// derived here (so we don't hard-depend on a daemon-side /summary that P1-A has
// not committed to). Pure functions, no I/O — trivially unit-testable later.

import {
  ALL_CATEGORIES,
  type Category,
  type Event,
} from "@/lib/contract/event";

export interface CategoryBucket {
  category: Category;
  ms: number;
  /** share of active time, 0..1 */
  pct: number;
}

export interface ProjectBucket {
  /** null project surfaces as "Unassigned" in the UI */
  project: string | null;
  ms: number;
  pct: number;
}

export interface FocusStats {
  /** 0..1 — share of active time spent in qualifying deep-work blocks */
  score: number;
  deepWorkMs: number;
  longestBlockMs: number;
  /** count of uninterrupted deep-work blocks >= MIN_FOCUS_BLOCK_MS */
  qualifyingBlocks: number;
  /** number of category changes across the day */
  contextSwitches: number;
}

export interface AiStats {
  costUsd: number;
  tokensIn: number;
  tokensOut: number;
  byModel: { model: string; costUsd: number; tokensIn: number; tokensOut: number }[];
  /** true when any token-source event was present */
  hasData: boolean;
}

export interface DaySummary {
  totalMs: number;
  activeMs: number;
  idleMs: number;
  byCategory: CategoryBucket[];
  topProjects: ProjectBucket[];
  focus: FocusStats;
  ai: AiStats;
  eventCount: number;
}

/** A deep-work block qualifies as "focused" at >= 25 uninterrupted minutes. */
export const MIN_FOCUS_BLOCK_MS = 25 * 60_000;
/** Gaps up to this length don't break a deep-work block (absorb micro-switches). */
const FOCUS_GAP_TOLERANCE_MS = 2 * 60_000;

function isActive(e: Event): boolean {
  return !e.is_idle && e.category !== "idle";
}

function computeFocus(events: Event[], activeMs: number): FocusStats {
  const deep = events
    .filter((e) => e.category === "deep_work")
    .sort((a, b) => Date.parse(a.ts_start) - Date.parse(b.ts_start));

  let deepWorkMs = 0;
  let longestBlockMs = 0;
  let qualifyingBlocks = 0;
  let focusedMs = 0;

  // Coalesce adjacent deep-work events (allowing a small gap) into blocks.
  let blockStart: number | null = null;
  let blockEnd = 0;

  const flush = () => {
    if (blockStart === null) return;
    const blockMs = blockEnd - blockStart;
    longestBlockMs = Math.max(longestBlockMs, blockMs);
    if (blockMs >= MIN_FOCUS_BLOCK_MS) {
      qualifyingBlocks += 1;
      focusedMs += blockMs;
    }
    blockStart = null;
  };

  for (const e of deep) {
    const start = Date.parse(e.ts_start);
    const end = Date.parse(e.ts_end);
    deepWorkMs += end - start;
    if (blockStart === null) {
      blockStart = start;
      blockEnd = end;
    } else if (start - blockEnd <= FOCUS_GAP_TOLERANCE_MS) {
      blockEnd = Math.max(blockEnd, end);
    } else {
      flush();
      blockStart = start;
      blockEnd = end;
    }
  }
  flush();

  // Context switches: category changes across the chronological active stream.
  const chrono = events
    .filter(isActive)
    .sort((a, b) => Date.parse(a.ts_start) - Date.parse(b.ts_start));
  let contextSwitches = 0;
  for (let i = 1; i < chrono.length; i++) {
    if (chrono[i].category !== chrono[i - 1].category) contextSwitches += 1;
  }

  return {
    score: activeMs > 0 ? focusedMs / activeMs : 0,
    deepWorkMs,
    longestBlockMs,
    qualifyingBlocks,
    contextSwitches,
  };
}

function computeAi(events: Event[]): AiStats {
  const tokenEvents = events.filter((e) => e.source === "token");
  const byModel = new Map<
    string,
    { model: string; costUsd: number; tokensIn: number; tokensOut: number }
  >();
  let costUsd = 0;
  let tokensIn = 0;
  let tokensOut = 0;

  for (const e of tokenEvents) {
    const m = e.meta;
    const cost = typeof m.cost_usd === "number" ? m.cost_usd : 0;
    const tin = typeof m.tokens_in === "number" ? m.tokens_in : 0;
    const tout = typeof m.tokens_out === "number" ? m.tokens_out : 0;
    const model = typeof m.model === "string" ? m.model : "unknown";
    costUsd += cost;
    tokensIn += tin;
    tokensOut += tout;
    const row = byModel.get(model) ?? {
      model,
      costUsd: 0,
      tokensIn: 0,
      tokensOut: 0,
    };
    row.costUsd += cost;
    row.tokensIn += tin;
    row.tokensOut += tout;
    byModel.set(model, row);
  }

  return {
    costUsd,
    tokensIn,
    tokensOut,
    byModel: [...byModel.values()].sort((a, b) => b.costUsd - a.costUsd),
    hasData: tokenEvents.length > 0,
  };
}

export function computeSummary(events: Event[]): DaySummary {
  let activeMs = 0;
  let idleMs = 0;
  const catMs = new Map<Category, number>();
  const projMs = new Map<string | null, number>();

  for (const e of events) {
    const ms = e.duration_ms;
    if (isActive(e)) {
      activeMs += ms;
      const c = e.category;
      if (c) catMs.set(c, (catMs.get(c) ?? 0) + ms);
      projMs.set(e.project, (projMs.get(e.project) ?? 0) + ms);
    } else {
      idleMs += ms;
    }
  }

  const byCategory: CategoryBucket[] = ALL_CATEGORIES.filter(
    (c) => c !== "idle",
  )
    .map((category) => ({
      category,
      ms: catMs.get(category) ?? 0,
      pct: activeMs > 0 ? (catMs.get(category) ?? 0) / activeMs : 0,
    }))
    .filter((b) => b.ms > 0)
    .sort((a, b) => b.ms - a.ms);

  const topProjects: ProjectBucket[] = [...projMs.entries()]
    .map(([project, ms]) => ({
      project,
      ms,
      pct: activeMs > 0 ? ms / activeMs : 0,
    }))
    .sort((a, b) => b.ms - a.ms);

  return {
    totalMs: activeMs + idleMs,
    activeMs,
    idleMs,
    byCategory,
    topProjects,
    focus: computeFocus(events, activeMs),
    ai: computeAi(events),
    eventCount: events.length,
  };
}

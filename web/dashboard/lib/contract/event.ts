// The Cadence Event Contract, mirrored in TypeScript.
//
// This is a faithful read-side mirror of the FROZEN Go contract in
// `agent/internal/event/event.go` (docs/00-SYSTEM-KNOWLEDGE.md §5). P1-A owns
// the contract; this file only consumes it. If the Go contract changes via a
// coordination event, update this mirror to match — never let it drift.
//
// Invariants carried over from the contract:
//   - Every key is ALWAYS present on the wire. Absent values are `null`, never
//     omitted. Nullable fields are typed `T | null` (not `T?`).
//   - `meta` is additive forever: unknown keys are preserved, never rejected.
//   - Timestamps are UTC RFC3339 strings; the UI localizes, storage never does.

export const SCHEMA_VERSION = 1;

/** Collector that produced an event. */
export type Source = "os" | "vscode" | "chrome" | "token" | "github";

export const ALL_SOURCES: readonly Source[] = [
  "os",
  "vscode",
  "chrome",
  "token",
  "github",
];

/** Semantic label set by the rule classifier; null pre-classification. */
export type Category =
  | "deep_work"
  | "meetings"
  | "comms"
  | "research"
  | "code_review"
  | "ai_assisted"
  | "idle"
  | "other";

/** Every category in frozen contract order. */
export const ALL_CATEGORIES: readonly Category[] = [
  "deep_work",
  "meetings",
  "comms",
  "research",
  "code_review",
  "ai_assisted",
  "idle",
  "other",
];

/** Human-facing labels for categories. */
export const CATEGORY_LABEL: Record<Category, string> = {
  deep_work: "Deep work",
  meetings: "Meetings",
  comms: "Comms",
  research: "Research",
  code_review: "Code review",
  ai_assisted: "AI assisted",
  idle: "Idle",
  other: "Other",
};

/**
 * Well-known `meta` keys (conventions, not a closed set). Token-source events
 * carry the AI cost signal — the product's wedge.
 */
export interface Meta {
  lang?: string;
  model?: string;
  tokens_in?: number;
  tokens_out?: number;
  cost_usd?: number;
  commit_sha?: string;
  repo?: string;
  // Additive forever — any other key may appear.
  [key: string]: unknown;
}

/** One atomic activity record. Field order/keys match the frozen wire shape. */
export interface Event {
  event_id: string;
  schema_ver: number;
  source: Source;
  member_id: string;
  /** RFC3339 UTC */
  ts_start: string;
  /** RFC3339 UTC */
  ts_end: string;
  duration_ms: number;
  app: string;
  title: string | null;
  url: string | null;
  project: string | null;
  category: Category | null;
  is_idle: boolean;
  meta: Meta;
}

/** True when the category is a recognized v1 value. */
export function isCategory(value: unknown): value is Category {
  return (
    typeof value === "string" &&
    (ALL_CATEGORIES as readonly string[]).includes(value)
  );
}

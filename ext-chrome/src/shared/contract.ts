// TypeScript mirror of the frozen Event Contract (docs/00-SYSTEM-KNOWLEDGE.md §5,
// implemented in Go at agent/internal/event). Only the pieces the Chrome
// collector needs are mirrored here; the Go package remains the source of truth.
// Do not drift these values — changing them is a coordination event.

/** Schema version stamped into every event. Bump only via a coordination event. */
export const SCHEMA_VER = 1;

/** The semantic category enum (contract §5). Nullable on the wire pre-classify. */
export type Category =
  | "deep_work"
  | "meetings"
  | "comms"
  | "research"
  | "code_review"
  | "ai_assisted"
  | "idle"
  | "other";

/** Every valid category, in contract order — for exhaustiveness checks/tests. */
export const CATEGORIES: readonly Category[] = [
  "deep_work",
  "meetings",
  "comms",
  "research",
  "code_review",
  "ai_assisted",
  "idle",
  "other",
];

/** Collector identifier. The Chrome collector always emits "chrome". */
export type Source = "os" | "vscode" | "chrome" | "token" | "github";

/**
 * One event in the frozen wire shape (mirror of the Go Event struct). Every key
 * is ALWAYS present; a value the collector can't fill is null, never omitted.
 * The daemon validates that duration_ms === (ts_end - ts_start) in whole ms, so
 * timestamps must be emitted at millisecond precision (Date#toISOString is).
 */
export interface Event {
  event_id: string;
  schema_ver: number;
  source: Source;
  member_id: string;
  /** RFC3339 UTC, millisecond precision. */
  ts_start: string;
  /** RFC3339 UTC, millisecond precision. */
  ts_end: string;
  duration_ms: number;
  app: string;
  title: string | null;
  url: string | null;
  project: string | null;
  category: Category | null;
  is_idle: boolean;
  meta: Record<string, unknown>;
}

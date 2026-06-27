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

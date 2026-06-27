// Shared constants for the Cadence Chrome collector.
//
// These encode the lifecycle decisions from the P1-C.1 exploration
// (ext-chrome/docs/01-requirements-exploration.md): MV3 service workers are
// ephemeral, so we never run timers — we record timestamps on transitions and
// use an alarms heartbeat to checkpoint long sessions and survive SW death.

/** Human-readable app name stamped into every event's `app` field (contract §5). */
export const BROWSER_APP_NAME = "Google Chrome";

/**
 * chrome.idle detection interval, in seconds. Minimum allowed by Chrome is 15.
 * Mirrors the daemon's idle posture (P1-A.2 uses a 300s threshold); here we
 * detect sooner and let the daemon own final idle/meeting reconciliation.
 */
export const IDLE_DETECTION_SECONDS = 60;

/** Name of the periodic heartbeat alarm (checkpoint long sessions + flush). */
export const HEARTBEAT_ALARM = "cadence-heartbeat";

/** Heartbeat period in minutes. ~1 min bounds worst-case data loss for a long
 *  single-tab session if the SW is killed between transitions. Chrome clamps
 *  released-extension alarm periods to a 1-minute minimum. */
export const HEARTBEAT_PERIOD_MINUTES = 1;

/**
 * Spans shorter than this are dropped as noise (rapid tab flicking while the
 * user hunts for the right tab). Real focus is longer than a second.
 */
export const MIN_SPAN_MS = 1_000;

/** Most events accepted in one POST /events call (matches the daemon's §6 cap). */
export const MAX_BATCH = 1000;

/** chrome.storage keys. `session` lives in in-memory session storage (cleared
 *  on browser restart); accumulated spans live in local storage so they survive
 *  SW termination until flushed to the daemon (P1-C.4). */
export const STORAGE_KEYS = {
  /** Live focus state (session storage). */
  liveState: "cadence.live",
  /** Completed-but-unflushed focus spans (local storage). */
  pendingSpans: "cadence.pendingSpans",
} as const;

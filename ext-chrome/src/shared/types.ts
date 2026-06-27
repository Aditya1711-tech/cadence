// Internal types for the Chrome collector. These are NOT the wire contract —
// the frozen Event Contract (agent/internal/event) is assembled from these in
// P1-C.4. Keeping an internal shape lets focus tracking (C.3) run before the
// emit/categorize stages exist.

/** Idle posture derived from chrome.idle.onStateChanged. */
export type IdleState = "active" | "idle" | "locked";

/**
 * The live focus pointer: the single focused-active tab the user is currently
 * on. Stored in chrome.storage.session and recomputed on every transition.
 * Null whenever the browser is unfocused, idle/locked, or on an untrackable
 * page (chrome://, file://, new tab, etc.).
 *
 * Timestamps are epoch milliseconds (Date.now() equivalents). They are passed
 * in to every handler rather than read from the clock inside, so the logic is
 * deterministic and unit-testable.
 */
export interface FocusSession {
  tabId: number;
  windowId: number;
  /** Full tab URL as seen by the collector. Minimized to origin/null at emit
   *  time per privacy policy (P1-C.2) — kept raw here only for the live span. */
  url: string;
  /** Hostname extracted from `url` (e.g. "github.com"); the classification key. */
  domain: string;
  /** Tab title at session open; may be redacted/dropped at emit time. */
  title: string | null;
  /** Epoch ms when this focus session began (or was last checkpointed). */
  startTs: number;
}

/**
 * Persisted live state. Holds the current focus session plus the two signals
 * that gate it (browser focus + idle posture), so a freshly-revived service
 * worker can reconcile without re-querying everything.
 */
export interface LiveState {
  session: FocusSession | null;
  browserFocused: boolean;
  idle: IdleState;
}

/**
 * A completed focus interval, accumulated in local storage until P1-C.4 turns
 * it into an Event Contract event and POSTs it to the daemon. duration_ms is
 * precomputed (endTs - startTs) to mirror the contract's precomputed field.
 */
export interface FocusSpan {
  tabId: number;
  url: string;
  domain: string;
  title: string | null;
  startTs: number;
  endTs: number;
  durationMs: number;
  /** True if the window closed because the user went idle/locked. */
  endedIdle: boolean;
}

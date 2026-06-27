// Persistence layer for the collector. Because the MV3 service worker can be
// terminated at any moment (P1-C.1), nothing the tracker needs may live in a
// module global — it all goes through here.
//
//   - Live state  -> chrome.storage.session  (in-memory, cleared on browser
//                    restart; the right home for the volatile focus pointer)
//   - Pending spans -> chrome.storage.local   (durable across SW death until
//                    P1-C.4 flushes them to the daemon)

import { STORAGE_KEYS } from "../shared/constants.js";
import type { FocusSpan, LiveState } from "../shared/types.js";

const DEFAULT_LIVE: LiveState = {
  session: null,
  browserFocused: true,
  idle: "active",
};

/** Reads the live focus state, returning sensible defaults on first run. */
export async function getLiveState(): Promise<LiveState> {
  const got = await chrome.storage.session.get(STORAGE_KEYS.liveState);
  const stored = got[STORAGE_KEYS.liveState] as LiveState | undefined;
  return stored ?? { ...DEFAULT_LIVE };
}

/** Writes the live focus state. */
export async function setLiveState(state: LiveState): Promise<void> {
  await chrome.storage.session.set({ [STORAGE_KEYS.liveState]: state });
}

/** Appends a completed span to the durable pending-spans buffer. */
export async function appendSpan(span: FocusSpan): Promise<void> {
  const spans = await getPendingSpans();
  spans.push(span);
  await chrome.storage.local.set({ [STORAGE_KEYS.pendingSpans]: spans });
}

/** Returns all spans awaiting flush to the daemon (P1-C.4). */
export async function getPendingSpans(): Promise<FocusSpan[]> {
  const got = await chrome.storage.local.get(STORAGE_KEYS.pendingSpans);
  return (got[STORAGE_KEYS.pendingSpans] as FocusSpan[] | undefined) ?? [];
}

/** Replaces the pending-spans buffer (e.g. with the un-flushed remainder). */
export async function setPendingSpans(spans: FocusSpan[]): Promise<void> {
  if (spans.length === 0) {
    await chrome.storage.local.remove(STORAGE_KEYS.pendingSpans);
    return;
  }
  await chrome.storage.local.set({ [STORAGE_KEYS.pendingSpans]: spans });
}

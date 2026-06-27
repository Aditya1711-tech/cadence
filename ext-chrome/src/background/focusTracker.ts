// Runtime wiring around the pure focusLogic state machine: reads the live state,
// resolves the currently-focused tab from chrome.tabs, and persists completed
// spans. All chrome.* access for focus tracking is concentrated here.

import { extractDomain } from "../shared/url.js";
import type { IdleState } from "../shared/types.js";
import { appendSpan, getLiveState, setLiveState } from "./storage.js";
import {
  checkpoint,
  decideTransition,
  isKeepableSpan,
  type TargetTab,
} from "./focusLogic.js";

/**
 * Resolves the focused-active tab — the active tab of the last focused window —
 * or null if there isn't one or it is not trackable (chrome://, file://, etc.).
 */
async function getFocusedActiveTab(): Promise<TargetTab | null> {
  let tab: chrome.tabs.Tab | undefined;
  try {
    [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
  } catch {
    return null;
  }
  if (!tab || tab.id === undefined || tab.windowId === undefined || !tab.url) return null;
  const domain = extractDomain(tab.url);
  if (domain === null) return null;
  return {
    tabId: tab.id,
    windowId: tab.windowId,
    url: tab.url,
    domain,
    title: tab.title ?? null,
  };
}

/** Persists a span if it survives the keepable-span filter. */
async function persistSpan(span: Awaited<ReturnType<typeof checkpoint>>["span"]): Promise<void> {
  if (span && isKeepableSpan(span)) await appendSpan(span);
}

/**
 * The single reconciliation step: align the stored session with reality. Called
 * after any event that could change what's focused (tab switch/nav, window
 * focus, idle). Safe to call redundantly — a no-op transition writes nothing.
 */
export async function reconcile(now: number): Promise<void> {
  const state = await getLiveState();
  const engaged = state.browserFocused && state.idle === "active";
  const target = engaged ? await getFocusedActiveTab() : null;
  const endedIdle = state.idle !== "active";
  const { nextSession, span } = decideTransition(state.session, target, now, endedIdle);
  await persistSpan(span);
  state.session = nextSession;
  await setLiveState(state);
}

/** Window focus gained/lost (WINDOW_ID_NONE => Chrome lost focus to another app). */
export async function onBrowserFocusChanged(focused: boolean, now: number): Promise<void> {
  const state = await getLiveState();
  state.browserFocused = focused;
  await setLiveState(state);
  await reconcile(now);
}

/** Idle posture changed (active | idle | locked). */
export async function onIdleChanged(idle: IdleState, now: number): Promise<void> {
  const state = await getLiveState();
  state.idle = idle;
  await setLiveState(state);
  await reconcile(now);
}

/**
 * Heartbeat: checkpoint the open session (cap long focuses, survive SW death),
 * then reconcile to recover any transition missed while the worker was asleep.
 */
export async function onHeartbeat(now: number): Promise<void> {
  const state = await getLiveState();
  const { nextSession, span } = checkpoint(state.session, now);
  await persistSpan(span);
  state.session = nextSession;
  await setLiveState(state);
  await reconcile(now);
}

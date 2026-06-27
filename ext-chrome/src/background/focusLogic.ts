// Pure focus-tracking state machine — no chrome.* calls, so it is fully
// deterministic and unit-testable (the runtime wiring lives in focusTracker.ts;
// behavioral verification in a real browser is P1-C.7).
//
// The model from P1-C.1: we never run a timer. Each relevant browser event
// produces a "target" (the tab that should be focused now, or null) and we diff
// it against the stored session, emitting a completed span on every change.

import { MIN_SPAN_MS } from "../shared/constants.js";
import type { FocusSession, FocusSpan } from "../shared/types.js";

/** The tab that should currently hold focus, resolved from chrome.tabs. */
export interface TargetTab {
  tabId: number;
  windowId: number;
  url: string;
  domain: string;
  title: string | null;
}

/** Result of a transition: the session to store next, and any span to persist. */
export interface Transition {
  nextSession: FocusSession | null;
  span: FocusSpan | null;
}

/** Opens a fresh session for `target`, starting the clock at `now`. */
export function openSession(target: TargetTab, now: number): FocusSession {
  return {
    tabId: target.tabId,
    windowId: target.windowId,
    url: target.url,
    domain: target.domain,
    title: target.title,
    startTs: now,
  };
}

/** Closes `session` at `endTs` into a completed span with precomputed duration. */
export function makeSpan(session: FocusSession, endTs: number, endedIdle: boolean): FocusSpan {
  return {
    tabId: session.tabId,
    url: session.url,
    domain: session.domain,
    title: session.title,
    startTs: session.startTs,
    endTs,
    durationMs: Math.max(0, endTs - session.startTs),
    endedIdle,
  };
}

/** Spans below MIN_SPAN_MS are tab-flicking noise and are dropped. */
export function isKeepableSpan(span: FocusSpan): boolean {
  return span.durationMs >= MIN_SPAN_MS;
}

/** Two foci are "the same" when both the tab and its domain match — an in-tab
 *  navigation to a new domain is a real transition; a same-domain reload isn't. */
function sameFocus(session: FocusSession, target: TargetTab): boolean {
  return session.tabId === target.tabId && session.domain === target.domain;
}

/**
 * Core transition. Given the stored session and the tab that should now be
 * focused (or null when the browser is unfocused / idle / on an untrackable
 * page), returns the next session and any completed span. `endedIdle` marks a
 * span that closed because the user went idle/locked rather than switched tabs.
 */
export function decideTransition(
  session: FocusSession | null,
  target: TargetTab | null,
  now: number,
  endedIdle: boolean,
): Transition {
  if (!target) {
    if (!session) return { nextSession: null, span: null };
    return { nextSession: null, span: makeSpan(session, now, endedIdle) };
  }
  if (!session) {
    return { nextSession: openSession(target, now), span: null };
  }
  if (sameFocus(session, target)) {
    return { nextSession: session, span: null };
  }
  return { nextSession: openSession(target, now), span: makeSpan(session, now, endedIdle) };
}

/**
 * Checkpoint a still-open session at `now`: emit the elapsed span and restart
 * the clock under the same identity. This caps a long single-tab focus into
 * heartbeat-sized chunks so almost nothing is lost if the service worker is
 * terminated before the next real transition.
 */
export function checkpoint(session: FocusSession | null, now: number): Transition {
  if (!session) return { nextSession: null, span: null };
  return { nextSession: { ...session, startTs: now }, span: makeSpan(session, now, false) };
}

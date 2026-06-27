// Service-worker entry point for the Cadence Chrome collector.
//
// MV3 service workers are ephemeral (P1-C.1): this module only *registers*
// listeners at top level (which Chrome requires for wake-up) and keeps no state
// in globals — every handler reads/writes chrome.storage via focusTracker.

import {
  HEARTBEAT_ALARM,
  HEARTBEAT_PERIOD_MINUTES,
  IDLE_DETECTION_SECONDS,
} from "../shared/constants.js";
import { SETTINGS_KEY } from "../shared/settings.js";
import { getLiveState, setLiveState } from "./storage.js";
import {
  onBrowserFocusChanged,
  onHeartbeat,
  onIdleChanged,
  reconcile,
} from "./focusTracker.js";
import { flush } from "./emit.js";

const now = (): number => Date.now();

// Chrome events can overlap across `await` points, and our state is a
// read-modify-write on chrome.storage. Funnel every handler through a single
// promise chain so each transition is atomic with respect to the others.
let chain: Promise<unknown> = Promise.resolve();
function serialize(fn: () => Promise<unknown>): void {
  chain = chain.then(fn, fn).catch(() => undefined);
}

/** One-time-ish setup: arm idle detection + the heartbeat, then align to the
 *  current world. Safe to run again on each wake (install/startup). */
async function init(): Promise<void> {
  chrome.idle.setDetectionInterval(IDLE_DETECTION_SECONDS);
  await chrome.alarms.create(HEARTBEAT_ALARM, {
    periodInMinutes: HEARTBEAT_PERIOD_MINUTES,
  });
  const state = await getLiveState();
  state.browserFocused = true;
  state.idle = await chrome.idle.queryState(IDLE_DETECTION_SECONDS);
  await setLiveState(state);
  await reconcile(now());
  await flush(); // ship anything left over from a previous session
}

chrome.runtime.onInstalled.addListener(() => serialize(init));
chrome.runtime.onStartup.addListener(() => serialize(init));

// Tab focus changes within a window.
chrome.tabs.onActivated.addListener(() => serialize(() => reconcile(now())));

// In-tab navigation that may change the domain.
chrome.tabs.onUpdated.addListener((_tabId, changeInfo) => {
  if (changeInfo.url) serialize(() => reconcile(now()));
});

// Active tab closed.
chrome.tabs.onRemoved.addListener(() => serialize(() => reconcile(now())));

// Switching browser windows, or Chrome losing focus to another app entirely.
chrome.windows.onFocusChanged.addListener((windowId) => {
  const focused = windowId !== chrome.windows.WINDOW_ID_NONE;
  serialize(() => onBrowserFocusChanged(focused, now()));
});

// User went idle/locked or came back.
chrome.idle.onStateChanged.addListener((idleState) => {
  serialize(() => onIdleChanged(idleState, now()));
});

// Settings changed in the popup (e.g. pause toggled) — reconcile immediately so
// pausing closes the open session now rather than at the next heartbeat.
chrome.storage.onChanged.addListener((changes, area) => {
  if (area === "local" && changes[SETTINGS_KEY]) {
    serialize(() => reconcile(now()));
  }
});

// Heartbeat: checkpoint long sessions, then flush completed spans to the daemon.
chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === HEARTBEAT_ALARM) {
    serialize(async () => {
      await onHeartbeat(now());
      await flush();
    });
  }
});

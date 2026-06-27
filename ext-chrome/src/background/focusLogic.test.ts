import test from "node:test";
import assert from "node:assert/strict";
import {
  checkpoint,
  decideTransition,
  isKeepableSpan,
  makeSpan,
  openSession,
  type TargetTab,
} from "./focusLogic.js";

const target = (tabId: number, domain: string): TargetTab => ({
  tabId,
  windowId: 1,
  url: `https://${domain}/x`,
  domain,
  title: "t",
});

test("opens a session when none exists and a target is present", () => {
  const { nextSession, span } = decideTransition(null, target(1, "a.com"), 1000, false);
  assert.equal(span, null);
  assert.equal(nextSession?.domain, "a.com");
  assert.equal(nextSession?.startTs, 1000);
});

test("no-op when the same tab+domain stays focused", () => {
  const s = openSession(target(1, "a.com"), 0);
  const { nextSession, span } = decideTransition(s, target(1, "a.com"), 5000, false);
  assert.equal(span, null);
  assert.equal(nextSession, s);
});

test("domain change closes a span with correct duration and reopens", () => {
  const s = openSession(target(1, "a.com"), 0);
  const { nextSession, span } = decideTransition(s, target(1, "b.com"), 5000, false);
  assert.equal(span?.durationMs, 5000);
  assert.equal(span?.domain, "a.com");
  assert.equal(nextSession?.domain, "b.com");
  assert.equal(nextSession?.startTs, 5000);
});

test("target null closes to null and flags endedIdle", () => {
  const s = openSession(target(1, "a.com"), 0);
  const { nextSession, span } = decideTransition(s, null, 3000, true);
  assert.equal(nextSession, null);
  assert.equal(span?.endedIdle, true);
  assert.equal(span?.durationMs, 3000);
});

test("null -> null is inert", () => {
  const { nextSession, span } = decideTransition(null, null, 1, false);
  assert.equal(nextSession, null);
  assert.equal(span, null);
});

test("checkpoint emits the elapsed span and restarts the clock", () => {
  const s = openSession(target(1, "a.com"), 1000);
  const { nextSession, span } = checkpoint(s, 4000);
  assert.equal(span?.durationMs, 3000);
  assert.equal(nextSession?.startTs, 4000);
  assert.equal(nextSession?.domain, "a.com");
});

test("isKeepableSpan drops sub-second flicks, keeps real focus", () => {
  const s = openSession(target(1, "a.com"), 0);
  assert.equal(isKeepableSpan(makeSpan(s, 500, false)), false);
  assert.equal(isKeepableSpan(makeSpan(s, 1000, false)), true);
});

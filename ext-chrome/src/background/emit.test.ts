import test from "node:test";
import assert from "node:assert/strict";
import { spanToEvent } from "./emit.js";
import { CATEGORIES, type Event } from "../shared/contract.js";
import type { FocusSpan } from "../shared/types.js";

const KEYS = [
  "event_id", "schema_ver", "source", "member_id", "ts_start", "ts_end",
  "duration_ms", "app", "title", "url", "project", "category", "is_idle", "meta",
];

// Port of the daemon's event.Validate(): the store rejects on any failure.
function validate(e: Event): string[] {
  const errs: string[] = [];
  if (e.schema_ver !== 1) errs.push("schema_ver");
  if (!e.event_id.trim()) errs.push("event_id");
  if (!["os", "vscode", "chrome", "token", "github"].includes(e.source)) errs.push("source");
  if (!e.member_id.trim()) errs.push("member_id");
  const t0 = Date.parse(e.ts_start), t1 = Date.parse(e.ts_end);
  if (Number.isNaN(t0) || Number.isNaN(t1)) errs.push("ts");
  if (t1 < t0) errs.push("ts order");
  if (e.duration_ms !== t1 - t0) errs.push(`duration ${e.duration_ms} != ${t1 - t0}`);
  if (!e.app) errs.push("app");
  if (e.category !== null && !CATEGORIES.includes(e.category)) errs.push("category");
  return errs;
}

const span: FocusSpan = {
  tabId: 7,
  url: "https://github.com/acme/repo/pull/42?tab=files",
  domain: "github.com",
  title: "Fix auth bug · Pull Request #42 · acme/repo",
  startTs: 1_750_000_000_000,
  endTs: 1_750_000_283_456,
  durationMs: 283_456,
  endedIdle: false,
};

test("domain_only event is Validate-clean", () => {
  const e = spanToEvent(span, "member-1", "domain_only");
  assert.deepEqual(validate(e), []);
});

test("every contract key is present", () => {
  const e = spanToEvent(span, "member-1", "domain_only");
  assert.deepEqual(Object.keys(e).sort(), [...KEYS].sort());
});

test("domain_only minimizes: origin-only url, null title", () => {
  const e = spanToEvent(span, "member-1", "domain_only");
  assert.equal(e.url, "https://github.com");
  assert.equal(e.title, null);
});

test("full keeps raw url + title", () => {
  const e = spanToEvent(span, "member-1", "full");
  assert.equal(e.url, span.url);
  assert.equal(e.title, span.title);
  assert.deepEqual(validate(e), []);
});

test("category classified from domain; chrome source; duration carried", () => {
  const e = spanToEvent(span, "member-1", "domain_only");
  assert.equal(e.category, "code_review");
  assert.equal(e.source, "chrome");
  assert.equal(e.duration_ms, 283_456);
});

test("unknown domain -> null category, idle carried, still valid", () => {
  const idle: FocusSpan = { ...span, url: "https://news.ycombinator.com/", domain: "news.ycombinator.com", endedIdle: true };
  const e = spanToEvent(idle, "m", "domain_only");
  assert.equal(e.category, null);
  assert.equal(e.is_idle, true);
  assert.deepEqual(validate(e), []);
});

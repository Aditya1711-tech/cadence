import test from "node:test";
import assert from "node:assert/strict";
import { domainOf, formatDuration, topSites } from "./sites.js";
import type { Event } from "../shared/contract.js";

const ev = (source: string, url: string | null, ms: number) =>
  ({ source, url, duration_ms: ms } as unknown as Event);

const events: Event[] = [
  ev("chrome", "https://github.com", 60_000),
  ev("chrome", "https://github.com", 30_000),
  ev("chrome", "https://news.ycombinator.com", 120_000),
  ev("vscode", "https://github.com", 999_999), // ignored: not chrome
  ev("chrome", null, 5_000), // ignored: no url
  ev("chrome", "https://meet.google.com", 3_661_000),
];

test("topSites merges same domain and excludes non-chrome / null url", () => {
  const top = topSites(events);
  assert.equal(top.find((s) => s.domain === "github.com")?.durationMs, 90_000);
  assert.equal(top.find((s) => s.domain === "news.ycombinator.com")?.durationMs, 120_000);
  assert.ok(!top.some((s) => s.durationMs === 999_999));
});

test("topSites sorts by time desc and respects the limit", () => {
  const top = topSites(events);
  assert.equal(top[0]?.domain, "meet.google.com");
  assert.ok((top[0]?.durationMs ?? 0) >= (top[1]?.durationMs ?? 0));
  assert.equal(topSites(events, 1).length, 1);
});

test("formatDuration renders h/m/s", () => {
  assert.equal(formatDuration(3_661_000), "1h 1m");
  assert.equal(formatDuration(45 * 60_000), "45m");
  assert.equal(formatDuration(30_000), "30s");
  assert.equal(formatDuration(0), "0s");
});

test("domainOf handles origin, full path, and null", () => {
  assert.equal(domainOf("https://github.com"), "github.com");
  assert.equal(domainOf("https://github.com/a/b?x=1"), "github.com");
  assert.equal(domainOf(null), null);
});

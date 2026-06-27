// Turns accumulated focus spans into Event Contract events and ships them to the
// daemon's loopback route (P1-C.4). Privacy policy (P1-C.2) is applied here as
// data minimization at the source; the daemon remains the authoritative
// enforcement point.

import { BROWSER_APP_NAME, MAX_BATCH } from "../shared/constants.js";
import { SCHEMA_VER, type Event } from "../shared/contract.js";
import { getSettings, type UrlPrivacy } from "../shared/settings.js";
import type { FocusSpan } from "../shared/types.js";
import { originOnly } from "../shared/url.js";
import { categorize } from "./categorize.js";
import { getMemberId } from "./identity.js";
import { getPendingSpans, setPendingSpans } from "./storage.js";

/**
 * Maps one completed span to a contract-valid event. Pure (aside from the random
 * event_id) so it can be table-tested against the daemon's Validate rules.
 *
 * Privacy: domain_only (default) emits origin-only url + null title; full emits
 * the raw url + title. category is classified from the domain; project is null
 * (the browser can't reliably attribute one). duration_ms is carried verbatim
 * and equals ts_end - ts_start in ms, which is what the daemon validates.
 */
export function spanToEvent(span: FocusSpan, memberId: string, urlPrivacy: UrlPrivacy): Event {
  const domainOnly = urlPrivacy !== "full";
  return {
    event_id: crypto.randomUUID(),
    schema_ver: SCHEMA_VER,
    source: "chrome",
    member_id: memberId,
    ts_start: new Date(span.startTs).toISOString(),
    ts_end: new Date(span.endTs).toISOString(),
    duration_ms: span.durationMs,
    app: BROWSER_APP_NAME,
    title: domainOnly ? null : span.title,
    url: domainOnly ? originOnly(span.url) : span.url,
    project: null,
    category: categorize(span.domain),
    is_idle: span.endedIdle,
    meta: {},
  };
}

/**
 * Flushes pending spans to the daemon. POSTs up to MAX_BATCH at a time; on a 2xx
 * it drops exactly the flushed spans and leaves the rest for the next flush. If
 * the daemon is unreachable (it may not be running), spans are kept and retried
 * — the collector never blocks or loses data. Safe to call from the serialized
 * handler chain (no concurrent span append interleaves a flush).
 */
export async function flush(): Promise<void> {
  const all = await getPendingSpans();
  if (all.length === 0) return;

  const batch = all.slice(0, MAX_BATCH);
  const { agentPort, urlPrivacy } = await getSettings();
  const memberId = await getMemberId();
  const events = batch.map((span) => spanToEvent(span, memberId, urlPrivacy));

  let accepted = false;
  try {
    const res = await fetch(`http://127.0.0.1:${agentPort}/events`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(events),
    });
    accepted = res.ok;
  } catch {
    accepted = false; // daemon down — keep spans, retry next heartbeat
  }

  if (accepted) {
    const remaining = (await getPendingSpans()).slice(batch.length);
    await setPendingSpans(remaining);
  }
}

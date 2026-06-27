// Pure helpers for the popup's "today's top sites" view. Kept free of chrome.*
// and DOM so they can be unit-tested directly (P1-C.7).

import type { Event } from "../shared/contract.js";

export interface SiteTotal {
  domain: string;
  durationMs: number;
}

/** Hostname of an event url (origin-only under domain_only, full under full). */
export function domainOf(url: string | null): string | null {
  if (!url) return null;
  try {
    return new URL(url).hostname || null;
  } catch {
    return null;
  }
}

/**
 * Aggregates chrome events into per-domain totals, sorted by time desc and
 * capped at `limit`. Non-chrome events and events without a resolvable domain
 * are ignored, so the popup can be pointed at the daemon's full timeline.
 */
export function topSites(events: Event[], limit = 8): SiteTotal[] {
  const totals = new Map<string, number>();
  for (const e of events) {
    if (e.source !== "chrome") continue;
    const domain = domainOf(e.url);
    if (!domain) continue;
    totals.set(domain, (totals.get(domain) ?? 0) + (e.duration_ms || 0));
  }
  return [...totals.entries()]
    .map(([domain, durationMs]) => ({ domain, durationMs }))
    .sort((a, b) => b.durationMs - a.durationMs)
    .slice(0, limit);
}

/** Human-friendly duration: "1h 23m", "45m", or "30s". */
export function formatDuration(ms: number): string {
  const totalSec = Math.round(ms / 1000);
  const h = Math.floor(totalSec / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m`;
  return `${s}s`;
}

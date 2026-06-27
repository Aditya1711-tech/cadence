// Timeline ribbon layout — pure geometry, no rendering. Converts a day's events
// into proportional segments + hour ticks the <Ribbon> SVG draws. The window is
// cropped to the first and last activity so empty pre-dawn / late-night hours
// don't shrink the working day (P1-D.1: "never imply empty hours are missing").

import { colorFor } from "@/lib/colors";
import type { Category, Event } from "@/lib/contract/event";

export interface RibbonSegment {
  key: string;
  /** 0..100 — left edge as a percentage of the cropped window */
  leftPct: number;
  /** 0..100 — width as a percentage of the cropped window */
  widthPct: number;
  category: Category | null;
  color: string;
  app: string;
  project: string | null;
  startIso: string;
  endIso: string;
  durationMs: number;
  isIdle: boolean;
}

export interface RibbonTick {
  /** 0..100 — position of the hour gridline */
  leftPct: number;
  /** local hour label, e.g. "9 AM" */
  label: string;
}

export interface RibbonLayout {
  startMs: number;
  endMs: number;
  segments: RibbonSegment[];
  ticks: RibbonTick[];
}

function hourLabel(d: Date): string {
  return d.toLocaleTimeString(undefined, { hour: "numeric" });
}

export function buildRibbon(events: Event[]): RibbonLayout | null {
  if (events.length === 0) return null;

  let startMs = Infinity;
  let endMs = -Infinity;
  for (const e of events) {
    startMs = Math.min(startMs, Date.parse(e.ts_start));
    endMs = Math.max(endMs, Date.parse(e.ts_end));
  }
  const span = endMs - startMs;
  if (span <= 0) return null;

  const segments: RibbonSegment[] = events
    .slice()
    .sort((a, b) => Date.parse(a.ts_start) - Date.parse(b.ts_start))
    .map((e) => {
      const s = Date.parse(e.ts_start);
      const en = Date.parse(e.ts_end);
      return {
        key: e.event_id,
        leftPct: ((s - startMs) / span) * 100,
        widthPct: Math.max(((en - s) / span) * 100, 0.4), // keep tiny events visible
        category: e.category,
        color: colorFor(e.category),
        app: e.app,
        project: e.project,
        startIso: e.ts_start,
        endIso: e.ts_end,
        durationMs: e.duration_ms,
        isIdle: e.is_idle || e.category === "idle",
      };
    });

  // Hour gridlines from the first whole local hour at/after start to end.
  const ticks: RibbonTick[] = [];
  const first = new Date(startMs);
  first.setMinutes(0, 0, 0);
  if (first.getTime() < startMs) first.setHours(first.getHours() + 1);
  for (let t = first.getTime(); t <= endMs; t += 3_600_000) {
    ticks.push({
      leftPct: ((t - startMs) / span) * 100,
      label: hourLabel(new Date(t)),
    });
  }

  return { startMs, endMs, segments, ticks };
}

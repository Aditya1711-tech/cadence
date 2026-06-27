// Synthetic day of activity for dev + UI verification (mock client).
//
// Anchored to the current local day so the dashboard always has "today" data to
// render. Shapes are exact Event Contract objects (§5). A few token-source
// events are included so the AI-cost panel renders in mock mode; the real
// Phase-1 daemon won't emit those yet (token watcher is P2-C), and the panel
// degrades to its empty state when absent.

import {
  SCHEMA_VERSION,
  type Category,
  type Event,
  type Meta,
  type Source,
} from "@/lib/contract/event";

const MEMBER_ID = "00000000-0000-4000-8000-000000000001";

/** Deterministic-ish id so the same fixture row keeps a stable key per process. */
let seq = 0;
function fixtureId(): string {
  seq += 1;
  const n = seq.toString(16).padStart(12, "0");
  return `fffffff0-0000-4000-8000-${n}`;
}

interface Spec {
  startMin: number; // minutes after local midnight
  durMin: number;
  source: Source;
  app: string;
  title?: string | null;
  url?: string | null;
  project?: string | null;
  category: Category;
  isIdle?: boolean;
  meta?: Meta;
}

function build(dayStart: Date, s: Spec): Event {
  const start = new Date(dayStart.getTime() + s.startMin * 60_000);
  const end = new Date(start.getTime() + s.durMin * 60_000);
  return {
    event_id: fixtureId(),
    schema_ver: SCHEMA_VERSION,
    source: s.source,
    member_id: MEMBER_ID,
    ts_start: start.toISOString(),
    ts_end: end.toISOString(),
    duration_ms: s.durMin * 60_000,
    app: s.app,
    title: s.title ?? null,
    url: s.url ?? null,
    project: s.project ?? null,
    category: s.category,
    is_idle: s.isIdle ?? false,
    meta: s.meta ?? {},
  };
}

// A believable engineer's day: focused morning, standup, review, comms,
// research, lunch break (idle), AI-assisted afternoon push.
const SPECS: Spec[] = [
  { startMin: 9 * 60, durMin: 52, source: "vscode", app: "Visual Studio Code", title: "auth.ts — cadence-api", project: "cadence-api", category: "deep_work", meta: { lang: "typescript" } },
  { startMin: 9 * 60 + 52, durMin: 8, source: "chrome", app: "Google Chrome", title: "TimescaleDB continuous aggregates", url: "https://docs.timescale.com", project: "cadence-api", category: "research" },
  { startMin: 10 * 60, durMin: 63, source: "vscode", app: "Visual Studio Code", title: "ingest_handler.go — cadence-api", project: "cadence-api", category: "deep_work", meta: { lang: "go" } },
  { startMin: 11 * 60 + 5, durMin: 15, source: "chrome", app: "Google Chrome", title: "Daily standup", url: "https://meet.google.com/abc-defg-hij", category: "meetings" },
  { startMin: 11 * 60 + 20, durMin: 28, source: "chrome", app: "Google Chrome", title: "PR #214 review", url: "https://github.com/org/cadence-api/pull/214", project: "cadence-api", category: "code_review" },
  { startMin: 11 * 60 + 48, durMin: 12, source: "chrome", app: "Slack", title: "#eng", category: "comms" },
  { startMin: 12 * 60, durMin: 47, source: "os", app: "Cadence", title: null, category: "idle", isIdle: true },
  { startMin: 12 * 60 + 47, durMin: 33, source: "vscode", app: "Visual Studio Code", title: "store.go — cadence-api", project: "cadence-api", category: "deep_work", meta: { lang: "go" } },
  { startMin: 13 * 60 + 20, durMin: 41, source: "token", app: "Claude Code", title: null, project: "cadence-api", category: "ai_assisted", meta: { model: "claude-sonnet-4", tokens_in: 184000, tokens_out: 28400, cost_usd: 0.92 } },
  { startMin: 14 * 60 + 1, durMin: 58, source: "vscode", app: "Visual Studio Code", title: "summary.ts — cadence-dashboard", project: "cadence-dashboard", category: "deep_work", meta: { lang: "typescript" } },
  { startMin: 15 * 60, durMin: 9, source: "chrome", app: "Slack", title: "#design", category: "comms" },
  { startMin: 15 * 60 + 9, durMin: 26, source: "token", app: "Claude Code", title: null, project: "cadence-dashboard", category: "ai_assisted", meta: { model: "claude-haiku-4", tokens_in: 52000, tokens_out: 9100, cost_usd: 0.14 } },
  { startMin: 15 * 60 + 35, durMin: 67, source: "vscode", app: "Visual Studio Code", title: "ribbon.tsx — cadence-dashboard", project: "cadence-dashboard", category: "deep_work", meta: { lang: "typescriptreact" } },
  { startMin: 16 * 60 + 42, durMin: 18, source: "chrome", app: "Google Chrome", title: "Linear — P1-D board", url: "https://linear.app", category: "other" },
];

/** Build the synthetic day relative to the current local midnight. */
export function sampleDay(): Event[] {
  seq = 0;
  const now = new Date();
  const dayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  return SPECS.map((s) => build(dayStart, s));
}

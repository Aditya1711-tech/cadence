// Derivations over an OrgSummary for the overview panels. Pure functions — the
// page fetches once and feeds the result to each panel.

import { CATEGORIES } from "@/lib/contract/types";
import type {
  CategoryBucket,
  DayBucket,
  MemberRollup,
  ModelBucket,
} from "@/lib/contract/types";

/** Category keys that actually appear across the days, in the canonical order. */
export function categoriesPresent(days: DayBucket[]): string[] {
  const seen = new Set<string>();
  for (const d of days) for (const c of d.by_category) seen.add(c.category);
  const ordered = CATEGORIES.filter((c) => seen.has(c));
  // include any non-enum categories the backend might emit, after the known set
  const extra = [...seen].filter((c) => !ordered.includes(c as never));
  return [...ordered, ...extra];
}

/** Quick lookup: total_ms for (day, category). */
export function cellMs(day: DayBucket, category: string): number {
  return day.by_category.find((c) => c.category === category)?.total_ms ?? 0;
}

/** Largest single (day, category) cell — used to scale heatmap intensity. */
export function maxCellMs(days: DayBucket[]): number {
  let max = 0;
  for (const d of days)
    for (const c of d.by_category) if (c.total_ms > max) max = c.total_ms;
  return max;
}

/** Sum the per-member token rollups into one org-wide token summary. */
export function aggregateTokens(byMember: MemberRollup[]): {
  totalCostUsd: number;
  byModel: ModelBucket[];
} {
  const models = new Map<string, ModelBucket>();
  let total = 0;
  for (const m of byMember) {
    total += m.tokens?.total_cost_usd ?? 0;
    for (const mb of m.tokens?.by_model ?? []) {
      const cur = models.get(mb.model);
      if (cur) {
        cur.cost_usd += mb.cost_usd;
        cur.tokens_in += mb.tokens_in;
        cur.tokens_out += mb.tokens_out;
      } else {
        models.set(mb.model, { ...mb });
      }
    }
  }
  const byModel = [...models.values()].sort((a, b) => b.cost_usd - a.cost_usd);
  return { totalCostUsd: total, byModel };
}

/** Total time (ms) across a list of category buckets. */
export function sumMs(buckets: CategoryBucket[]): number {
  return buckets.reduce((acc, b) => acc + b.total_ms, 0);
}

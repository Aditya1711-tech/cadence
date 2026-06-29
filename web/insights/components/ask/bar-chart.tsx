"use client";

import type { NlQueryResponse } from "@/lib/contract/types";

type Cell = string | number | boolean | null;

function isNumeric(v: Cell): v is number {
  return typeof v === "number" && Number.isFinite(v);
}

/**
 * A lightweight horizontal bar chart, rendered only when the result looks like a
 * label→value breakdown: exactly two columns whose second column is numeric on
 * every row. Otherwise returns null and the table carries the result. No chart
 * library — a hand-rolled SVG, matching the dashboard/admin approach.
 */
export function BarChart({ result }: { result: NlQueryResponse }) {
  if (result.columns.length !== 2 || result.rows.length === 0) return null;
  if (!result.rows.every((r) => isNumeric(r[1]))) return null;

  const rows = result.rows
    .map((r) => ({ label: String(r[0] ?? "—"), value: r[1] as number }))
    .slice(0, 25);
  const max = Math.max(...rows.map((r) => Math.abs(r.value)), 1);

  return (
    <div className="space-y-1.5">
      {rows.map((r, i) => (
        <div key={i} className="flex items-center gap-2 text-sm">
          <span className="w-40 shrink-0 truncate text-slate-600 dark:text-slate-300" title={r.label}>
            {r.label}
          </span>
          <span className="relative h-5 flex-1 overflow-hidden rounded bg-slate-100 dark:bg-slate-800">
            <span
              className="absolute inset-y-0 left-0 rounded bg-blue-500/80"
              style={{ width: `${(Math.abs(r.value) / max) * 100}%` }}
            />
          </span>
          <span className="w-20 shrink-0 text-right tabular-nums text-slate-700 dark:text-slate-200">
            {formatNumber(r.value)}
          </span>
        </div>
      ))}
    </div>
  );
}

function formatNumber(n: number): string {
  if (Number.isInteger(n)) return n.toLocaleString();
  return n.toLocaleString(undefined, { maximumFractionDigits: 2 });
}

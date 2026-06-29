"use client";

import type { NlQueryResponse } from "@/lib/contract/types";
import { Card, CardTitle } from "@/components/ui/card";
import { BarChart } from "@/components/ask/bar-chart";

type Cell = string | number | boolean | null;

function renderCell(v: Cell): string {
  if (v === null || v === undefined) return "—";
  if (typeof v === "number") return v.toLocaleString(undefined, { maximumFractionDigits: 3 });
  return String(v);
}

export function ResultView({ result }: { result: NlQueryResponse }) {
  return (
    <div className="space-y-4">
      {result.caption ? (
        <Card>
          <p className="text-base leading-relaxed text-slate-800 dark:text-slate-100">
            {result.caption}
          </p>
        </Card>
      ) : null}

      {result.truncated ? (
        <p className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-sm
          text-amber-800 dark:border-amber-900 dark:bg-amber-950 dark:text-amber-200">
          Showing the first {result.rows.length} rows — the result was capped.
        </p>
      ) : null}

      {result.rows.length === 0 ? (
        <Card className="text-center text-sm text-slate-500 dark:text-slate-400">
          No matching activity for that question.
        </Card>
      ) : (
        <>
          {result.columns.length === 2 ? (
            <Card>
              <CardTitle>Breakdown</CardTitle>
              <BarChart result={result} />
            </Card>
          ) : null}

          <Card>
            <CardTitle>Result ({result.row_count} rows)</CardTitle>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead>
                  <tr className="border-b border-slate-200 dark:border-slate-700">
                    {result.columns.map((c) => (
                      <th key={c} className="px-2 py-1.5 font-medium text-slate-500 dark:text-slate-400">
                        {c}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {result.rows.map((row, ri) => (
                    <tr key={ri} className="border-b border-slate-100 dark:border-slate-800">
                      {row.map((cell, ci) => (
                        <td key={ci} className="px-2 py-1.5 tabular-nums text-slate-700 dark:text-slate-200">
                          {renderCell(cell)}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>
        </>
      )}

      <details className="text-sm text-slate-500 dark:text-slate-400">
        <summary className="cursor-pointer select-none">View the SQL that ran</summary>
        <pre className="mt-2 overflow-x-auto rounded-lg bg-slate-100 p-3 text-xs
          text-slate-700 dark:bg-slate-800 dark:text-slate-200">
          <code>{result.sql}</code>
        </pre>
      </details>
    </div>
  );
}

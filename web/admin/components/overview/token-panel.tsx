import { Card, CardTitle } from "@/components/ui/card";
import { formatCost, formatTokens } from "@/lib/format";
import { aggregateTokens } from "@/lib/summary";
import type { MemberRollup, PrivacyLevel } from "@/lib/contract/types";

/**
 * AI token spend — the wedge metric. Aggregated from per-member rollups, so it
 * is only available when the privacy level exposes per-member data. Under
 * aggregate_only the backend returns no per-member tokens; we say so plainly.
 */
export function TokenPanel({
  byMember,
  privacyLevel,
}: {
  byMember: MemberRollup[];
  privacyLevel: PrivacyLevel;
}) {
  const { totalCostUsd, byModel } = aggregateTokens(byMember);

  return (
    <Card>
      <CardTitle>AI token spend</CardTitle>

      {privacyLevel === "aggregate_only" ? (
        <p className="text-sm text-slate-500 dark:text-slate-400">
          Token spend detail is hidden at the <strong>aggregate only</strong>{" "}
          privacy level.
        </p>
      ) : byModel.length === 0 ? (
        <p className="text-sm text-slate-500 dark:text-slate-400">
          No AI token usage recorded in this range yet.
        </p>
      ) : (
        <>
          <div className="mb-4">
            <div className="text-3xl font-semibold tracking-tight text-slate-900 dark:text-slate-50">
              {formatCost(totalCostUsd)}
            </div>
            <div className="text-xs text-slate-500 dark:text-slate-400">
              total across {byModel.length} model
              {byModel.length === 1 ? "" : "s"}
            </div>
          </div>
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-xs uppercase tracking-wide text-slate-400">
                <th className="py-1 font-medium">Model</th>
                <th className="py-1 text-right font-medium">In</th>
                <th className="py-1 text-right font-medium">Out</th>
                <th className="py-1 text-right font-medium">Cost</th>
              </tr>
            </thead>
            <tbody>
              {byModel.map((m) => (
                <tr
                  key={m.model}
                  className="border-t border-slate-100 dark:border-slate-800"
                >
                  <td className="py-1.5 font-medium text-slate-700 dark:text-slate-200">
                    {m.model}
                  </td>
                  <td className="py-1.5 text-right text-slate-500 dark:text-slate-400">
                    {formatTokens(m.tokens_in)}
                  </td>
                  <td className="py-1.5 text-right text-slate-500 dark:text-slate-400">
                    {formatTokens(m.tokens_out)}
                  </td>
                  <td className="py-1.5 text-right text-slate-700 dark:text-slate-200">
                    {formatCost(m.cost_usd)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </Card>
  );
}

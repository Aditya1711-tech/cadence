import { Card, CardTitle } from "@/components/ui/card";
import { colorFor, categoryLabel } from "@/lib/colors";
import { formatDuration } from "@/lib/format";
import { sumMs } from "@/lib/summary";
import type { CategoryBucket } from "@/lib/contract/types";

/** Where the team's time went — ranked horizontal bars by category. */
export function CategoryTotals({ totals }: { totals: CategoryBucket[] }) {
  const ranked = [...totals].sort((a, b) => b.total_ms - a.total_ms);
  const total = sumMs(ranked);
  const max = ranked[0]?.total_ms ?? 0;

  return (
    <Card>
      <CardTitle>Where time went</CardTitle>
      {total === 0 ? (
        <p className="text-sm text-slate-500 dark:text-slate-400">
          No activity in this range yet.
        </p>
      ) : (
        <ul className="space-y-2.5">
          {ranked.map((c) => {
            const pct = total > 0 ? Math.round((c.total_ms / total) * 100) : 0;
            const width = max > 0 ? (c.total_ms / max) * 100 : 0;
            return (
              <li key={c.category}>
                <div className="mb-1 flex items-center justify-between text-sm">
                  <span className="font-medium text-slate-700 dark:text-slate-200">
                    {categoryLabel(c.category)}
                  </span>
                  <span className="text-slate-500 dark:text-slate-400">
                    {formatDuration(c.total_ms)} · {pct}%
                  </span>
                </div>
                <div className="h-2 w-full rounded-full bg-slate-100 dark:bg-slate-800">
                  <div
                    className="h-2 rounded-full"
                    style={{
                      width: `${width}%`,
                      backgroundColor: colorFor(c.category),
                    }}
                  />
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </Card>
  );
}

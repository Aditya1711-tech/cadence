import { Card, CardTitle } from "@/components/ui/card";
import { colorFor, categoryLabel } from "@/lib/colors";
import { formatDuration } from "@/lib/format";
import { categoriesPresent, cellMs, maxCellMs } from "@/lib/summary";
import type { DayBucket } from "@/lib/contract/types";

/** Short day label (e.g. "Mon 12") from an ISO date, defensively. */
function dayLabel(iso: string): string {
  const d = new Date(iso + "T00:00:00");
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleDateString(undefined, { weekday: "short", day: "numeric" });
}

/**
 * Team category heatmap: rows = categories, columns = days. Cell opacity scales
 * with that cell's time relative to the busiest cell, so the picture reads as
 * "where the team's hours land over time". Aggregate, never per-person.
 */
export function Heatmap({ days }: { days: DayBucket[] }) {
  const cats = categoriesPresent(days);
  const max = maxCellMs(days);

  if (days.length === 0 || cats.length === 0) {
    return (
      <Card>
        <CardTitle>Category heatmap</CardTitle>
        <p className="text-sm text-slate-500 dark:text-slate-400">
          No daily activity in this range yet.
        </p>
      </Card>
    );
  }

  return (
    <Card>
      <CardTitle>Category heatmap</CardTitle>
      <div className="overflow-x-auto">
        <table className="border-separate border-spacing-1">
          <thead>
            <tr>
              <th className="w-28" />
              {days.map((d) => (
                <th
                  key={d.date}
                  className="px-1 text-center text-[11px] font-normal text-slate-400"
                >
                  {dayLabel(d.date)}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {cats.map((cat) => (
              <tr key={cat}>
                <td className="pr-2 text-right text-xs font-medium text-slate-600 dark:text-slate-300">
                  <span className="inline-flex items-center gap-1.5">
                    <span
                      className="inline-block h-2.5 w-2.5 rounded-sm"
                      style={{ backgroundColor: colorFor(cat) }}
                    />
                    {categoryLabel(cat)}
                  </span>
                </td>
                {days.map((d) => {
                  const ms = cellMs(d, cat);
                  const alpha = max > 0 ? Math.max(ms / max, ms > 0 ? 0.12 : 0) : 0;
                  return (
                    <td key={d.date} className="p-0">
                      <div
                        title={`${categoryLabel(cat)} · ${dayLabel(d.date)} · ${formatDuration(ms)}`}
                        className="h-7 w-9 rounded-sm border border-slate-100 dark:border-slate-800"
                        style={
                          alpha > 0
                            ? { backgroundColor: colorFor(cat), opacity: alpha }
                            : undefined
                        }
                      />
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="mt-3 text-xs text-slate-400">
        Darker = more team time in that category that day. Hover a cell for hours.
      </p>
    </Card>
  );
}

import type { ProjectBucket } from "@/lib/summary";
import { formatDuration } from "@/lib/time";

// Top projects (P1-D.5): ranked horizontal bars by tracked time. Null project
// surfaces as "Unassigned" rather than being hidden, so the totals reconcile.
export function TopProjects({
  projects,
  limit = 5,
}: {
  projects: ProjectBucket[];
  limit?: number;
}) {
  const top = projects.slice(0, limit);
  const max = top.reduce((m, p) => Math.max(m, p.ms), 0) || 1;

  return (
    <ul className="space-y-3">
      {top.map((p) => {
        const name = p.project ?? "Unassigned";
        return (
          <li key={name}>
            <div className="mb-1 flex items-baseline justify-between text-sm">
              <span
                className={
                  "truncate " +
                  (p.project ? "" : "italic text-slate-400")
                }
              >
                {name}
              </span>
              <span className="ml-2 shrink-0 tabular-nums text-slate-500 dark:text-slate-400">
                {formatDuration(p.ms)}
              </span>
            </div>
            <div className="h-2 w-full overflow-hidden rounded-full bg-slate-100 dark:bg-slate-800">
              <div
                className="h-full rounded-full bg-cat-deep_work"
                style={{ width: `${(p.ms / max) * 100}%` }}
              />
            </div>
          </li>
        );
      })}
    </ul>
  );
}

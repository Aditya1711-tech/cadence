import { Card, CardTitle } from "@/components/ui/card";
import { Ribbon } from "@/components/ribbon";
import { CategoryDonut } from "@/components/category-donut";
import { TopProjects } from "@/components/top-projects";
import { FocusCard } from "@/components/focus-card";
import { formatDuration } from "@/lib/time";
import type { DaySummary } from "@/lib/summary";
import type { Event } from "@/lib/contract/event";

// The populated dashboard. Pure/presentational so it renders identically on the
// server (initial SSR) and the client (after a poll refresh).
export function DayView({
  events,
  summary,
}: {
  events: Event[];
  summary: DaySummary;
}) {
  return (
    <div className="space-y-6">
      <Card>
        <p className="text-sm font-medium text-slate-500 dark:text-slate-400">
          Deep work today
        </p>
        <p className="mt-1 text-5xl font-semibold tracking-tight text-cat-deep_work">
          {formatDuration(summary.focus.deepWorkMs)}
        </p>
        <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">
          {formatDuration(summary.activeMs)} active ·{" "}
          {formatDuration(summary.idleMs)} idle
        </p>
      </Card>

      <Card>
        <CardTitle>Timeline</CardTitle>
        <Ribbon events={events} />
      </Card>

      <Card>
        <CardTitle>Focus</CardTitle>
        <FocusCard focus={summary.focus} />
      </Card>

      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <CardTitle>Categories</CardTitle>
          <CategoryDonut
            buckets={summary.byCategory}
            centerLabel="active"
            centerValue={formatDuration(summary.activeMs)}
          />
        </Card>
        <Card>
          <CardTitle>Top projects</CardTitle>
          <TopProjects projects={summary.topProjects} />
        </Card>
      </div>

      <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <Stat label="Tracked" value={formatDuration(summary.totalMs)} />
        <Stat label="Active" value={formatDuration(summary.activeMs)} />
        <Stat label="Idle" value={formatDuration(summary.idleMs)} />
        <Stat label="Events" value={String(summary.eventCount)} />
      </div>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <Card>
      <p className="text-xs text-slate-500 dark:text-slate-400">{label}</p>
      <p className="mt-1 text-2xl font-semibold tracking-tight">{value}</p>
    </Card>
  );
}

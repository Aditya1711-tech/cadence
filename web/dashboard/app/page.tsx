import { loadDay } from "@/lib/dashboard-data";
import { Card, CardTitle } from "@/components/ui/card";
import { Ribbon } from "@/components/ribbon";
import { CategoryDonut } from "@/components/category-donut";
import { TopProjects } from "@/components/top-projects";
import { FocusCard } from "@/components/focus-card";
import { formatDuration } from "@/lib/time";
import type { DaySummary } from "@/lib/summary";
import type { Event } from "@/lib/contract/event";

// Server component: reads today's local activity and renders the glanceable
// view — hero deep-work stat, timeline ribbon (P1-D.4), focus score (P1-D.6),
// category breakdown + top projects (P1-D.5), and totals. Friendly offline /
// empty states are refined in P1-D.7.
export const dynamic = "force-dynamic";

export default async function TodayPage() {
  const day = await loadDay();
  const today = new Date().toLocaleDateString(undefined, {
    weekday: "long",
    month: "short",
    day: "numeric",
  });

  return (
    <main className="mx-auto max-w-5xl px-4 py-8 sm:px-6">
      <header className="mb-6 flex items-baseline justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Today</h1>
          <p className="text-sm text-slate-500 dark:text-slate-400">{today}</p>
        </div>
        <span className="rounded-full bg-slate-100 px-3 py-1 text-xs text-slate-500 dark:bg-slate-800 dark:text-slate-400">
          local · private
        </span>
      </header>

      {day.status === "offline" ? (
        <Notice
          title="Cadence isn’t running"
          body="Start the Cadence agent to see your day. Your data never leaves this machine."
        />
      ) : day.status === "error" ? (
        <Notice title="Couldn’t read your activity" body={day.message} />
      ) : day.summary.eventCount === 0 ? (
        <Notice
          title="Nothing tracked yet today"
          body="Tracking is live — your timeline will fill in as you work."
        />
      ) : (
        <DayView events={day.events} summary={day.summary} />
      )}
    </main>
  );
}

function DayView({
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

function Notice({ title, body }: { title: string; body: string }) {
  return (
    <Card className="text-center">
      <p className="text-lg font-medium">{title}</p>
      <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">{body}</p>
    </Card>
  );
}

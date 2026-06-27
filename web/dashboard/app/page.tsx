import { loadDay } from "@/lib/dashboard-data";
import { Card } from "@/components/ui/card";
import { formatDuration } from "@/lib/time";

// Server component: reads today's local activity and renders the glanceable
// view. Ribbon (P1-D.4), category donut + projects (P1-D.5), and focus score
// (P1-D.6) are layered into this page in their own tasks; this commit (P1-D.3)
// establishes the read path and the hero/totals.
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
        <DayView
          focusedMs={day.summary.focus.deepWorkMs}
          activeMs={day.summary.activeMs}
          idleMs={day.summary.idleMs}
          totalMs={day.summary.totalMs}
          eventCount={day.summary.eventCount}
        />
      )}
    </main>
  );
}

function DayView({
  focusedMs,
  activeMs,
  idleMs,
  totalMs,
  eventCount,
}: {
  focusedMs: number;
  activeMs: number;
  idleMs: number;
  totalMs: number;
  eventCount: number;
}) {
  return (
    <div className="space-y-6">
      <Card>
        <p className="text-sm font-medium text-slate-500 dark:text-slate-400">
          Deep work today
        </p>
        <p className="mt-1 text-5xl font-semibold tracking-tight text-cat-deep_work">
          {formatDuration(focusedMs)}
        </p>
        <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">
          {formatDuration(activeMs)} active · {formatDuration(idleMs)} idle
        </p>
      </Card>

      <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <Stat label="Tracked" value={formatDuration(totalMs)} />
        <Stat label="Active" value={formatDuration(activeMs)} />
        <Stat label="Idle" value={formatDuration(idleMs)} />
        <Stat label="Events" value={String(eventCount)} />
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

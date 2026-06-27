import { loadDay } from "@/lib/dashboard-data";
import { LiveDay, type InitialDay } from "@/components/live-day";

// Server component: does the initial local read (fast first paint + SSR of the
// offline/empty states with no JS), then hands off to LiveDay which keeps the
// view current by polling /api/timeline.
export const dynamic = "force-dynamic";

export default async function TodayPage() {
  const day = await loadDay();
  const today = new Date().toLocaleDateString(undefined, {
    weekday: "long",
    month: "short",
    day: "numeric",
  });

  const initial: InitialDay =
    day.status === "ok"
      ? { status: "ok", events: day.events }
      : { status: day.status, events: [], message: day.message };

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

      <LiveDay initial={initial} />
    </main>
  );
}

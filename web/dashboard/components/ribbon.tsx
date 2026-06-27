import { buildRibbon } from "@/lib/ribbon";
import { CATEGORY_LABEL, type Event } from "@/lib/contract/event";
import { formatDuration, formatLocalTime } from "@/lib/time";

// Daily timeline ribbon (P1-D.4): a single horizontal bar, cropped to the
// working window, with blocks colored by category. Native title tooltips keep
// it glanceable with zero JS. Server-rendered.
export function Ribbon({ events }: { events: Event[] }) {
  const layout = buildRibbon(events);
  if (!layout) return null;

  return (
    <div>
      <div className="relative h-12 w-full overflow-hidden rounded-lg bg-slate-100 dark:bg-slate-800">
        {/* hour gridlines */}
        {layout.ticks.map((t, i) => (
          <div
            key={`tick-${i}`}
            className="absolute top-0 h-full w-px bg-slate-300/60 dark:bg-slate-700/60"
            style={{ left: `${t.leftPct}%` }}
            aria-hidden
          />
        ))}
        {/* activity blocks */}
        {layout.segments.map((s) => {
          const label = `${s.app}${s.project ? ` · ${s.project}` : ""} — ${
            s.category ? CATEGORY_LABEL[s.category] : "Uncategorized"
          }\n${formatLocalTime(s.startIso)}–${formatLocalTime(
            s.endIso,
          )} (${formatDuration(s.durationMs)})`;
          return (
            <div
              key={s.key}
              className="absolute top-0 h-full"
              style={{
                left: `${s.leftPct}%`,
                width: `${s.widthPct}%`,
                backgroundColor: s.color,
                opacity: s.isIdle ? 0.45 : 1,
              }}
              title={label}
            />
          );
        })}
      </div>
      {/* hour labels */}
      <div className="relative mt-1 h-4 w-full text-[10px] text-slate-400">
        {layout.ticks.map((t, i) => (
          <span
            key={`lbl-${i}`}
            className="absolute -translate-x-1/2"
            style={{ left: `${t.leftPct}%` }}
          >
            {t.label}
          </span>
        ))}
      </div>
    </div>
  );
}

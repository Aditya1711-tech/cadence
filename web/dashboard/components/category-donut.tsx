import { CATEGORY_COLOR } from "@/lib/colors";
import { CATEGORY_LABEL } from "@/lib/contract/event";
import type { CategoryBucket } from "@/lib/summary";
import { formatDuration } from "@/lib/time";

// Category breakdown (P1-D.5): an SVG donut + legend. Colors match the ribbon
// so the two read as one picture. Server-rendered; the legend carries the exact
// durations and percentages so the donut needs no interactivity.

const RADIUS = 60;
const STROKE = 22;
const CIRC = 2 * Math.PI * RADIUS;

export function CategoryDonut({
  buckets,
  centerLabel,
  centerValue,
}: {
  buckets: CategoryBucket[];
  centerLabel: string;
  centerValue: string;
}) {
  // Accumulate arc offsets around the ring.
  let offset = 0;
  const arcs = buckets.map((b) => {
    const len = b.pct * CIRC;
    const arc = {
      category: b.category,
      color: CATEGORY_COLOR[b.category],
      dash: `${len} ${CIRC - len}`,
      // negative offset advances clockwise from 12 o'clock
      dashOffset: -offset,
    };
    offset += len;
    return arc;
  });

  return (
    <div className="flex flex-col items-center gap-5 sm:flex-row sm:items-center">
      <svg
        viewBox="0 0 160 160"
        className="h-40 w-40 shrink-0 -rotate-90"
        role="img"
        aria-label="Category breakdown"
      >
        <circle
          cx="80"
          cy="80"
          r={RADIUS}
          fill="none"
          stroke="currentColor"
          strokeWidth={STROKE}
          className="text-slate-100 dark:text-slate-800"
        />
        {arcs.map((a) => (
          <circle
            key={a.category}
            cx="80"
            cy="80"
            r={RADIUS}
            fill="none"
            stroke={a.color}
            strokeWidth={STROKE}
            strokeDasharray={a.dash}
            strokeDashoffset={a.dashOffset}
          />
        ))}
        {/* center label is drawn upright despite the -90° group rotation */}
        <g transform="rotate(90 80 80)">
          <text
            x="80"
            y="74"
            textAnchor="middle"
            className="fill-slate-900 text-[15px] font-semibold dark:fill-slate-100"
          >
            {centerValue}
          </text>
          <text
            x="80"
            y="92"
            textAnchor="middle"
            className="fill-slate-400 text-[9px] uppercase tracking-wide"
          >
            {centerLabel}
          </text>
        </g>
      </svg>

      <ul className="w-full space-y-1.5">
        {buckets.map((b) => (
          <li
            key={b.category}
            className="flex items-center gap-2 text-sm"
          >
            <span
              className="inline-block h-3 w-3 shrink-0 rounded-sm"
              style={{ backgroundColor: CATEGORY_COLOR[b.category] }}
              aria-hidden
            />
            <span className="flex-1 truncate">{CATEGORY_LABEL[b.category]}</span>
            <span className="tabular-nums text-slate-500 dark:text-slate-400">
              {formatDuration(b.ms)}
            </span>
            <span className="w-10 text-right tabular-nums text-slate-400">
              {Math.round(b.pct * 100)}%
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

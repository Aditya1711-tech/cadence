import type { FocusStats } from "@/lib/summary";
import { formatDuration } from "@/lib/time";

// Focus score (P1-D.6): one 0–100 number plus a plain-language read, so the
// score is legible rather than a black box. Score = share of active time spent
// in uninterrupted deep-work blocks >= 25 min (see lib/summary.ts).

function band(score: number): { label: string; color: string } {
  if (score >= 0.6) return { label: "Focused", color: "#059669" }; // emerald
  if (score >= 0.3) return { label: "Mixed", color: "#d97706" }; // amber
  return { label: "Fragmented", color: "#db2777" }; // pink
}

export function FocusCard({ focus }: { focus: FocusStats }) {
  const pct = Math.round(focus.score * 100);
  const { label, color } = band(focus.score);

  // Plain-language summary of what drives the score.
  const blocks =
    focus.qualifyingBlocks === 1 ? "1 deep block" : `${focus.qualifyingBlocks} deep blocks`;
  const longest =
    focus.longestBlockMs > 0
      ? `longest ${formatDuration(focus.longestBlockMs)}`
      : "no block ≥ 25m";
  const switches =
    focus.contextSwitches === 1
      ? "1 context switch"
      : `${focus.contextSwitches} context switches`;

  return (
    <div className="flex items-center gap-5">
      <div className="flex flex-col items-center">
        <span
          className="text-5xl font-semibold tabular-nums leading-none"
          style={{ color }}
        >
          {pct}
        </span>
        <span
          className="mt-1 text-xs font-medium uppercase tracking-wide"
          style={{ color }}
        >
          {label}
        </span>
      </div>
      <div className="flex-1">
        <div className="h-2 w-full overflow-hidden rounded-full bg-slate-100 dark:bg-slate-800">
          <div
            className="h-full rounded-full"
            style={{ width: `${pct}%`, backgroundColor: color }}
          />
        </div>
        <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">
          {blocks} · {longest} · {switches}
        </p>
        <p className="mt-0.5 text-xs text-slate-400">
          {formatDuration(focus.deepWorkMs)} of deep work today
        </p>
      </div>
    </div>
  );
}

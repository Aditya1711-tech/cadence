// Small presentation helpers shared across admin panels.

/** Milliseconds → compact "Xh Ym" / "Ym" / "0m". */
export function formatDuration(ms: number): string {
  const totalMin = Math.round(ms / 60000);
  if (totalMin <= 0) return "0m";
  const h = Math.floor(totalMin / 60);
  const m = totalMin % 60;
  if (h === 0) return `${m}m`;
  if (m === 0) return `${h}h`;
  return `${h}h ${m}m`;
}

/** Milliseconds → hours with one decimal (for axis labels / totals). */
export function msToHours(ms: number): number {
  return Math.round((ms / 3600000) * 10) / 10;
}

/** USD number → "$0.045" (3dp under a dollar, else 2dp). */
export function formatCost(usd: number): string {
  if (!Number.isFinite(usd)) return "$0.00";
  const dp = usd > 0 && usd < 1 ? 3 : 2;
  return `$${usd.toFixed(dp)}`;
}

/** Large token counts → "12.0k" / "1.2M" / "950". */
export function formatTokens(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`;
  return `${n}`;
}

/** ISO timestamp → local short date+time, defensively. */
export function formatWhen(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString(undefined, {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
  });
}

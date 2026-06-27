// Time helpers. The store is UTC; the dashboard localizes for display and
// resolves the user's local-day boundaries to absolute instants before querying
// (§5: storage never localizes).

/** Local midnight (00:00) of the day containing `d`. */
export function startOfLocalDay(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate());
}

/** Local midnight of the following day (exclusive end of the day window). */
export function endOfLocalDay(d: Date): Date {
  const s = startOfLocalDay(d);
  return new Date(s.getFullYear(), s.getMonth(), s.getDate() + 1);
}

/** Format a millisecond duration as a compact "4h 12m" / "47m" / "0m". */
export function formatDuration(ms: number): string {
  const totalMin = Math.round(ms / 60_000);
  const h = Math.floor(totalMin / 60);
  const m = totalMin % 60;
  if (h > 0 && m > 0) return `${h}h ${m}m`;
  if (h > 0) return `${h}h`;
  return `${m}m`;
}

/** Format a USD amount as "$0.92" / "$1.2k". */
export function formatUsd(usd: number): string {
  if (usd >= 1000) return `$${(usd / 1000).toFixed(1)}k`;
  return `$${usd.toFixed(2)}`;
}

/** Format a token count as "184k" / "1.2M" / "920". */
export function formatCount(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${Math.round(n / 1_000)}k`;
  return `${n}`;
}

/** "9:14 AM" style local time-of-day for a UTC instant string. */
export function formatLocalTime(iso: string): string {
  return new Date(iso).toLocaleTimeString(undefined, {
    hour: "numeric",
    minute: "2-digit",
  });
}

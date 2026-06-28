import type { PrivacyLevel } from "@/lib/contract/types";

const COPY: Record<PrivacyLevel, { label: string; detail: string; tone: string }> = {
  full: {
    label: "Full detail",
    detail:
      "Admins can see app names, window titles, URLs and projects. Use only with explicit team consent.",
    tone: "border-amber-300 bg-amber-50 text-amber-900 dark:border-amber-900 dark:bg-amber-950 dark:text-amber-200",
  },
  categories_only: {
    label: "Categories only",
    detail:
      "Admins see categories, durations and projects — never window titles or URLs.",
    tone: "border-emerald-300 bg-emerald-50 text-emerald-900 dark:border-emerald-900 dark:bg-emerald-950 dark:text-emerald-200",
  },
  aggregate_only: {
    label: "Aggregate only",
    detail:
      "Admins see daily category totals for the team only — no per-member detail at all.",
    tone: "border-blue-300 bg-blue-50 text-blue-900 dark:border-blue-900 dark:bg-blue-950 dark:text-blue-200",
  },
};

/**
 * Always-visible statement of what this org's privacy level exposes — the trust
 * contract, surfaced so the admin (and the team) always knows the boundary (§8).
 */
export function PrivacyBanner({ level }: { level: PrivacyLevel }) {
  const c = COPY[level] ?? COPY.categories_only;
  return (
    <div className={`rounded-lg border px-4 py-2 text-sm ${c.tone}`}>
      <span className="font-semibold">Privacy: {c.label}.</span>{" "}
      <span className="opacity-90">{c.detail}</span>
    </div>
  );
}

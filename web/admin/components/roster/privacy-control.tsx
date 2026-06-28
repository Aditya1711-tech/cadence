import { Card, CardTitle } from "@/components/ui/card";
import type { PrivacyLevel } from "@/lib/contract/types";

const LEVELS: { value: PrivacyLevel; label: string; detail: string }[] = [
  {
    value: "full",
    label: "Full detail",
    detail: "App names, window titles, URLs and projects.",
  },
  {
    value: "categories_only",
    label: "Categories only",
    detail: "Categories, durations and projects. No titles or URLs.",
  },
  {
    value: "aggregate_only",
    label: "Aggregate only",
    detail: "Team daily category totals only. No per-member detail.",
  },
];

/**
 * P2-E.4 — org privacy level. READ-ONLY for now: the backend exposes the level
 * but no endpoint to change it yet (NEEDS P2-E → P2-A in PROGRESS.md). The
 * control renders the current choice and the trade-offs so the admin understands
 * the trust contract; the radios are disabled until the set-endpoint lands.
 */
export function PrivacyControl({ level }: { level: PrivacyLevel }) {
  return (
    <Card>
      <CardTitle>Privacy level</CardTitle>
      <fieldset className="space-y-2">
        {LEVELS.map((l) => {
          const selected = l.value === level;
          return (
            <label
              key={l.value}
              className={
                "flex cursor-not-allowed items-start gap-3 rounded-lg border p-3 " +
                (selected
                  ? "border-blue-300 bg-blue-50 dark:border-blue-800 dark:bg-blue-950"
                  : "border-slate-200 opacity-70 dark:border-slate-800")
              }
            >
              <input
                type="radio"
                name="privacy"
                checked={selected}
                disabled
                readOnly
                className="mt-1"
              />
              <span>
                <span className="block text-sm font-medium text-slate-800 dark:text-slate-100">
                  {l.label}
                  {selected ? (
                    <span className="ml-2 text-xs font-normal text-blue-600 dark:text-blue-300">
                      current
                    </span>
                  ) : null}
                </span>
                <span className="block text-xs text-slate-500 dark:text-slate-400">
                  {l.detail}
                </span>
              </span>
            </label>
          );
        })}
      </fieldset>
      <p className="mt-3 text-xs text-slate-500 dark:text-slate-400">
        Changing the privacy level is coming soon. It is set when the org is
        created; contact support to change it in the meantime.
      </p>
    </Card>
  );
}

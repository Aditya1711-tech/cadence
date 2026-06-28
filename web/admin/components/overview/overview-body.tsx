"use client";

import { useEffect, useState } from "react";
import { RangePicker } from "@/components/overview/range-picker";
import { Heatmap } from "@/components/overview/heatmap";
import { CategoryTotals } from "@/components/overview/category-totals";
import { TokenPanel } from "@/components/overview/token-panel";
import { CommitPanel } from "@/components/overview/commit-panel";
import { Loading, ErrorState } from "@/components/ui/states";
import { apiGet, ApiError } from "@/lib/client";
import type { OrgSummary, Range } from "@/lib/contract/types";

/** P2-E.5 — the team summary: heatmap + token spend + commit activity. */
export function OverviewBody() {
  const [range, setRange] = useState<Range>("7d");
  const [summary, setSummary] = useState<OrgSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    let live = true;
    setLoading(true);
    setError(null);
    apiGet<OrgSummary>(`/api/org/summary?range=${range}`)
      .then((s) => {
        if (live) setSummary(s);
      })
      .catch((err) => {
        if (live)
          setError(err instanceof ApiError ? err.message : "Could not load summary");
      })
      .finally(() => {
        if (live) setLoading(false);
      });
    return () => {
      live = false;
    };
  }, [range, reloadKey]);

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">Team overview</h1>
          <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
            Aggregate team health and AI spend. Member-level detail lives in the
            roster, within the org privacy level.
          </p>
        </div>
        <RangePicker value={range} onChange={setRange} />
      </div>

      {loading && !summary ? (
        <Loading label="Loading team summary…" />
      ) : error ? (
        <ErrorState message={error} onRetry={() => setReloadKey((k) => k + 1)} />
      ) : summary ? (
        <div className="space-y-6">
          <Heatmap days={summary.org_by_day} />
          <div className="grid gap-6 lg:grid-cols-3">
            <CategoryTotals totals={summary.org_totals_by_category} />
            <TokenPanel
              byMember={summary.by_member}
              privacyLevel={summary.privacy_level}
            />
            <CommitPanel />
          </div>
        </div>
      ) : null}
    </div>
  );
}

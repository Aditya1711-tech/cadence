"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { Card, CardTitle } from "@/components/ui/card";
import { Badge, statusTone, roleTone } from "@/components/ui/badge";
import { Loading, ErrorState, EmptyState } from "@/components/ui/states";
import { RangePicker } from "@/components/overview/range-picker";
import { CategoryTotals } from "@/components/overview/category-totals";
import { TokenPanel } from "@/components/overview/token-panel";
import { apiGet, ApiError } from "@/lib/client";
import type {
  MemberRollup,
  MemberSummary,
  MembersResponse,
  OrgSummary,
  Range,
} from "@/lib/contract/types";

/**
 * P2-E.6 — per-member drilldown, privacy-bounded BY CONSTRUCTION. It renders the
 * member's slice of /org/summary (by_member) — category mix + token spend only,
 * never window titles / URLs / per-event detail. Under aggregate_only the
 * backend returns no per-member data, so we say so plainly.
 */
export function MemberDrilldown({ memberId }: { memberId: string }) {
  const [range, setRange] = useState<Range>("7d");
  const [summary, setSummary] = useState<OrgSummary | null>(null);
  const [identity, setIdentity] = useState<MemberSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    let live = true;
    setLoading(true);
    setError(null);
    Promise.all([
      apiGet<OrgSummary>(`/api/org/summary?range=${range}`),
      // Identity for the header; cap-limited fetch covers a 5–50 dev org.
      apiGet<MembersResponse>(`/api/org/members?limit=1000`).catch(() => null),
    ])
      .then(([s, members]) => {
        if (!live) return;
        setSummary(s);
        setIdentity(
          members?.items.find((m) => m.member_id === memberId) ?? null,
        );
      })
      .catch((err) => {
        if (live)
          setError(err instanceof ApiError ? err.message : "Could not load member");
      })
      .finally(() => {
        if (live) setLoading(false);
      });
    return () => {
      live = false;
    };
  }, [range, memberId, reloadKey]);

  const rollup: MemberRollup | undefined = summary?.by_member.find(
    (m) => m.member_id === memberId,
  );
  const displayName =
    identity?.display_name || rollup?.display_name || identity?.email || "Member";

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <Link
            href="/roster"
            className="text-sm text-blue-600 hover:underline"
          >
            ← Back to roster
          </Link>
          <h1 className="mt-1 text-xl font-semibold tracking-tight">
            {displayName}
          </h1>
          <div className="mt-1 flex flex-wrap items-center gap-2 text-sm text-slate-500 dark:text-slate-400">
            {identity?.email ? <span>{identity.email}</span> : null}
            {identity ? <Badge tone={roleTone(identity.role)}>{identity.role}</Badge> : null}
            {identity ? (
              <Badge tone={statusTone(identity.status)}>{identity.status}</Badge>
            ) : null}
            {identity?.teams.length ? (
              <span>· {identity.teams.join(", ")}</span>
            ) : null}
          </div>
        </div>
        <RangePicker value={range} onChange={setRange} />
      </div>

      {loading && !summary ? (
        <Loading label="Loading member…" />
      ) : error ? (
        <ErrorState message={error} onRetry={() => setReloadKey((k) => k + 1)} />
      ) : summary?.privacy_level === "aggregate_only" ? (
        <Card>
          <CardTitle>Per-member detail hidden</CardTitle>
          <p className="text-sm text-slate-600 dark:text-slate-300">
            This org&apos;s privacy level is <strong>aggregate only</strong>, so
            individual member breakdowns aren&apos;t available. Team-wide totals
            are on the{" "}
            <Link href="/overview" className="text-blue-600 hover:underline">
              overview
            </Link>
            .
          </p>
        </Card>
      ) : !rollup ? (
        <EmptyState title="No activity in this range">
          This member hasn&apos;t synced any activity for the selected period.
        </EmptyState>
      ) : (
        <div className="grid gap-6 lg:grid-cols-2">
          <CategoryTotals totals={rollup.by_category} title="Time by category" />
          <TokenPanel byMember={[rollup]} privacyLevel={summary!.privacy_level} />
        </div>
      )}

      <p className="text-xs text-slate-400">
        Member views show category mix and AI spend only — never window titles,
        URLs, or keystroke-level detail.
      </p>
    </div>
  );
}

"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { Card, CardTitle } from "@/components/ui/card";
import { Badge, statusTone, roleTone } from "@/components/ui/badge";
import { Loading, ErrorState, EmptyState } from "@/components/ui/states";
import { apiGet, ApiError } from "@/lib/client";
import type { MemberSummary, MembersResponse } from "@/lib/contract/types";

/** P2-E.4 — paginated team roster. Each row links to the member drilldown. */
export function RosterList({ refreshKey = 0 }: { refreshKey?: number }) {
  const [members, setMembers] = useState<MemberSummary[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (after: string | null) => {
    const qs = new URLSearchParams({ limit: "100" });
    if (after) qs.set("cursor", after);
    return apiGet<MembersResponse>(`/api/org/members?${qs.toString()}`);
  }, []);

  const reload = useCallback(() => {
    setLoading(true);
    setError(null);
    load(null)
      .then((r) => {
        setMembers(r.items);
        setCursor(r.next_cursor);
      })
      .catch((err) =>
        setError(err instanceof ApiError ? err.message : "Could not load roster"),
      )
      .finally(() => setLoading(false));
  }, [load]);

  useEffect(() => {
    reload();
  }, [reload, refreshKey]);

  async function loadMore() {
    if (!cursor) return;
    setLoadingMore(true);
    try {
      const r = await load(cursor);
      setMembers((prev) => [...prev, ...r.items]);
      setCursor(r.next_cursor);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not load more");
    } finally {
      setLoadingMore(false);
    }
  }

  if (loading) return <Loading label="Loading roster…" />;
  if (error) return <ErrorState message={error} onRetry={reload} />;
  if (members.length === 0) {
    return (
      <EmptyState title="No members yet">
        Invite your team to get started — they appear here once they accept.
      </EmptyState>
    );
  }

  return (
    <Card>
      <CardTitle>Members ({members.length})</CardTitle>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-slate-200 text-left text-xs uppercase tracking-wide text-slate-400 dark:border-slate-800">
              <th className="py-2 pr-4 font-medium">Member</th>
              <th className="py-2 pr-4 font-medium">Role</th>
              <th className="py-2 pr-4 font-medium">Status</th>
              <th className="py-2 pr-4 font-medium">Teams</th>
              <th className="py-2 font-medium"></th>
            </tr>
          </thead>
          <tbody>
            {members.map((m) => (
              <tr
                key={m.member_id}
                className="border-b border-slate-100 last:border-0 dark:border-slate-800"
              >
                <td className="py-2.5 pr-4">
                  <div className="font-medium text-slate-800 dark:text-slate-100">
                    {m.display_name || "—"}
                  </div>
                  <div className="text-xs text-slate-500 dark:text-slate-400">
                    {m.email}
                  </div>
                </td>
                <td className="py-2.5 pr-4">
                  <Badge tone={roleTone(m.role)}>{m.role}</Badge>
                </td>
                <td className="py-2.5 pr-4">
                  <Badge tone={statusTone(m.status)}>{m.status}</Badge>
                </td>
                <td className="py-2.5 pr-4 text-slate-600 dark:text-slate-300">
                  {m.teams.length ? m.teams.join(", ") : "—"}
                </td>
                <td className="py-2.5 text-right">
                  <Link
                    href={`/members/${m.member_id}`}
                    className="text-sm font-medium text-blue-600 hover:underline"
                  >
                    View
                  </Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {cursor ? (
        <button
          onClick={loadMore}
          disabled={loadingMore}
          className="mt-4 rounded-lg border border-slate-300 px-3 py-1.5 text-sm font-medium
            text-slate-700 hover:bg-slate-100 disabled:opacity-60
            dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-800"
        >
          {loadingMore ? "Loading…" : "Load more"}
        </button>
      ) : null}
    </Card>
  );
}

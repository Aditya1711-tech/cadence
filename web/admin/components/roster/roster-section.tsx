"use client";

import { useState } from "react";
import { InvitePanel } from "@/components/roster/invite-panel";
import { PrivacyControl } from "@/components/roster/privacy-control";
import { RosterList } from "@/components/roster/roster-list";
import type { PrivacyLevel } from "@/lib/contract/types";

/**
 * Client orchestration for the roster page: an invite creation refreshes the
 * roster list (shared refreshKey). Privacy control is read-only (interim).
 */
export function RosterSection({ level }: { level: PrivacyLevel }) {
  const [refreshKey, setRefreshKey] = useState(0);
  return (
    <div className="grid gap-6 lg:grid-cols-3">
      <div className="space-y-6 lg:col-span-1">
        <InvitePanel onCreated={() => setRefreshKey((k) => k + 1)} />
        <PrivacyControl level={level} />
      </div>
      <div className="lg:col-span-2">
        <RosterList refreshKey={refreshKey} />
      </div>
    </div>
  );
}

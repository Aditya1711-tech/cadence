import { redirect } from "next/navigation";
import { readSession } from "@/lib/api/session";
import { RosterSection } from "@/components/roster/roster-section";

// P2-E.4 — roster + invite management + privacy-level control.
export default function RosterPage() {
  const s = readSession();
  if (!s) redirect("/login");

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Roster</h1>
        <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
          Invite teammates and manage who has access. Member detail honors the
          org privacy level.
        </p>
      </div>
      <RosterSection level={s.org.privacy_level} />
    </div>
  );
}

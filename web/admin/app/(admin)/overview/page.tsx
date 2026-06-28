import { Card, CardTitle } from "@/components/ui/card";

// Placeholder — the real team summary (heatmap + token spend + commit activity)
// lands in P2-E.5. Kept minimal so post-login has a real landing page.
export default function OverviewPage() {
  return (
    <div className="space-y-6">
      <h1 className="text-xl font-semibold tracking-tight">Team overview</h1>
      <Card>
        <CardTitle>Coming up</CardTitle>
        <p className="text-sm text-slate-600 dark:text-slate-300">
          Team category heatmap, AI token spend and commit activity will appear
          here once members start syncing. Invite your team from the{" "}
          <a href="/roster" className="text-blue-600 hover:underline">
            Roster
          </a>{" "}
          page to get started.
        </p>
      </Card>
    </div>
  );
}

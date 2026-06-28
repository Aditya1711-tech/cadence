import { Card, CardTitle } from "@/components/ui/card";

/**
 * Commit activity (P2-E.5). The /org/summary contract has no commit/github
 * block yet, and GitHub ingestion (P2-D) is a separate, not-yet-built stream
 * (see NEEDS P2-E → P2-A in PROGRESS.md). Until both land, this renders an
 * honest "not connected" state rather than faking data.
 */
export function CommitPanel() {
  return (
    <Card>
      <CardTitle>Commit activity</CardTitle>
      <div className="rounded-lg border border-dashed border-slate-300 px-4 py-6 text-center dark:border-slate-700">
        <p className="text-sm font-medium text-slate-600 dark:text-slate-300">
          GitHub not connected
        </p>
        <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
          Once the GitHub integration is set up, your team&apos;s commit activity
          will appear here alongside time and AI spend.
        </p>
      </div>
    </Card>
  );
}

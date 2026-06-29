"use client";

import { useState } from "react";
import type { NlQueryResponse } from "@/lib/contract/types";
import { Card } from "@/components/ui/card";
import { Button, ErrorNote } from "@/components/ui/form";
import { ResultView } from "@/components/ask/result-view";
import { apiPost, ApiError } from "@/lib/client";

const EXAMPLES = [
  "How much did we code vs meet last week?",
  "Which AI models cost the most this month?",
  "Show daily deep-work hours over the last 30 days.",
  "How many commits did each person make in the last 7 days?",
  "What's our total AI token spend this month?",
];

export default function AskPage() {
  const [question, setQuestion] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<NlQueryResponse | null>(null);

  async function run(q: string) {
    const trimmed = q.trim();
    if (!trimmed || busy) return;
    setBusy(true);
    setError(null);
    try {
      const res = await apiPost<NlQueryResponse>("/api/query/nl", { question: trimmed });
      setResult(res);
    } catch (err) {
      setResult(null);
      setError(err instanceof ApiError ? err.message : "Query failed");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-6">
      <Card>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            run(question);
          }}
          className="space-y-3"
        >
          <label className="block">
            <span className="mb-1 block text-sm font-medium text-slate-700 dark:text-slate-300">
              Ask about your team&apos;s activity
            </span>
            <textarea
              value={question}
              onChange={(e) => setQuestion(e.target.value)}
              rows={2}
              placeholder="e.g. How much did we code vs meet last week?"
              className="w-full resize-y rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm
                text-slate-900 outline-none ring-blue-500 focus:ring-2
                dark:border-slate-700 dark:bg-slate-950 dark:text-slate-100"
            />
          </label>
          <div className="flex items-center justify-between gap-3">
            <p className="text-xs text-slate-400">
              Answers come from SQL run read-only and scoped to your org. Aggregates only — never raw activity.
            </p>
            <Button type="submit" busy={busy}>
              Ask
            </Button>
          </div>
        </form>

        <div className="mt-4">
          <p className="mb-2 text-xs font-medium uppercase tracking-wide text-slate-400">
            Try
          </p>
          <div className="flex flex-wrap gap-2">
            {EXAMPLES.map((ex) => (
              <button
                key={ex}
                type="button"
                disabled={busy}
                onClick={() => {
                  setQuestion(ex);
                  run(ex);
                }}
                className="rounded-full border border-slate-300 px-3 py-1 text-xs
                  text-slate-600 transition hover:bg-slate-100 disabled:opacity-60
                  dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800"
              >
                {ex}
              </button>
            ))}
          </div>
        </div>
      </Card>

      <ErrorNote>{error}</ErrorNote>

      {busy ? (
        <Card>
          <div className="flex items-center gap-3 text-sm text-slate-500 dark:text-slate-400">
            <span className="h-2 w-2 animate-pulse rounded-full bg-slate-400" />
            Thinking…
          </div>
        </Card>
      ) : null}

      {!busy && result ? <ResultView result={result} /> : null}
    </div>
  );
}

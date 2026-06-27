"use client";

import { Card } from "@/components/ui/card";

// Friendly, non-scary states (P1-D.7). Offline is the common first-run case, so
// it explains how to start the agent and offers a retry. Loading is a skeleton,
// not a blocking spinner.

export type StateKind = "offline" | "empty" | "error" | "loading";

export function StatePanel({
  kind,
  message,
  onRetry,
  busy = false,
}: {
  kind: StateKind;
  message?: string;
  onRetry?: () => void;
  busy?: boolean;
}) {
  if (kind === "loading") return <SkeletonDay />;

  const copy = {
    offline: {
      icon: "🟠",
      title: "Cadence isn’t running",
      body: "Start the Cadence agent to see your day. Your data never leaves this machine.",
      hint: "cadence-agent",
    },
    empty: {
      icon: "🌱",
      title: "Nothing tracked yet today",
      body: "Tracking is live — your timeline will fill in as you work.",
      hint: null,
    },
    error: {
      icon: "⚠️",
      title: "Couldn’t read your activity",
      body: message ?? "Something went wrong reading the local store.",
      hint: null,
    },
  }[kind];

  return (
    <Card className="text-center">
      <div className="mx-auto max-w-md py-6">
        <div className="text-3xl" aria-hidden>
          {copy.icon}
        </div>
        <p className="mt-3 text-lg font-medium">{copy.title}</p>
        <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
          {copy.body}
        </p>
        {copy.hint ? (
          <code className="mt-3 inline-block rounded-md bg-slate-100 px-2 py-1 text-xs text-slate-600 dark:bg-slate-800 dark:text-slate-300">
            {copy.hint}
          </code>
        ) : null}
        {onRetry ? (
          <div className="mt-5">
            <button
              type="button"
              onClick={onRetry}
              disabled={busy}
              className="rounded-md border border-slate-300 px-4 py-1.5 text-sm font-medium text-slate-700 transition hover:bg-slate-50 disabled:opacity-50 dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-800"
            >
              {busy ? "Checking…" : "Retry"}
            </button>
          </div>
        ) : null}
      </div>
    </Card>
  );
}

function SkeletonDay() {
  return (
    <div className="space-y-6" aria-busy="true" aria-label="Loading your day">
      <Card>
        <div className="h-4 w-28 animate-pulse rounded bg-slate-200 dark:bg-slate-800" />
        <div className="mt-3 h-12 w-40 animate-pulse rounded bg-slate-200 dark:bg-slate-800" />
      </Card>
      <Card>
        <div className="h-12 w-full animate-pulse rounded-lg bg-slate-200 dark:bg-slate-800" />
      </Card>
      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <div className="h-40 w-full animate-pulse rounded bg-slate-200 dark:bg-slate-800" />
        </Card>
        <Card>
          <div className="h-40 w-full animate-pulse rounded bg-slate-200 dark:bg-slate-800" />
        </Card>
      </div>
    </div>
  );
}

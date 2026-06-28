import { type ReactNode } from "react";
import { Card } from "@/components/ui/card";

/** Loading skeleton placeholder. */
export function Loading({ label = "Loading…" }: { label?: string }) {
  return (
    <Card>
      <div className="flex items-center gap-3 text-sm text-slate-500 dark:text-slate-400">
        <span className="h-2 w-2 animate-pulse rounded-full bg-slate-400" />
        {label}
      </div>
    </Card>
  );
}

/** Error panel with optional retry. */
export function ErrorState({
  message,
  onRetry,
}: {
  message: string;
  onRetry?: () => void;
}) {
  return (
    <Card className="border-red-200 dark:border-red-900">
      <p className="text-sm text-red-700 dark:text-red-300">{message}</p>
      {onRetry ? (
        <button
          onClick={onRetry}
          className="mt-3 rounded-lg border border-slate-300 px-3 py-1.5 text-sm
            font-medium text-slate-700 hover:bg-slate-100
            dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-800"
        >
          Try again
        </button>
      ) : null}
    </Card>
  );
}

/** Friendly empty state. */
export function EmptyState({
  title,
  children,
}: {
  title: string;
  children?: ReactNode;
}) {
  return (
    <Card className="text-center">
      <p className="font-medium text-slate-700 dark:text-slate-200">{title}</p>
      {children ? (
        <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">{children}</p>
      ) : null}
    </Card>
  );
}

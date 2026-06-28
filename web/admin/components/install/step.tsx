import { type ReactNode } from "react";
import { Card } from "@/components/ui/card";

/** Numbered onboarding step card. */
export function Step({
  n,
  title,
  children,
}: {
  n: number;
  title: string;
  children: ReactNode;
}) {
  return (
    <Card>
      <div className="flex items-start gap-3">
        <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-blue-600 text-sm font-semibold text-white">
          {n}
        </span>
        <div className="min-w-0 flex-1">
          <h3 className="mb-2 text-base font-semibold tracking-tight">{title}</h3>
          <div className="space-y-3 text-sm text-slate-600 dark:text-slate-300">
            {children}
          </div>
        </div>
      </div>
    </Card>
  );
}

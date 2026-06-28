import { type ReactNode } from "react";

/** Minimal surface primitive shared across admin panels (mirrors the dashboard). */
export function Card({
  children,
  className = "",
}: {
  children: ReactNode;
  className?: string;
}) {
  return (
    <div
      className={
        "rounded-xl border border-slate-200 bg-white p-5 shadow-sm " +
        "dark:border-slate-800 dark:bg-slate-900 " +
        className
      }
    >
      {children}
    </div>
  );
}

export function CardTitle({ children }: { children: ReactNode }) {
  return (
    <h2 className="mb-3 text-sm font-medium text-slate-500 dark:text-slate-400">
      {children}
    </h2>
  );
}

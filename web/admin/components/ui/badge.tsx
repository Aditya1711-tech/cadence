import { type ReactNode } from "react";

const TONES: Record<string, string> = {
  neutral: "bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300",
  green: "bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300",
  amber: "bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300",
  blue: "bg-blue-100 text-blue-800 dark:bg-blue-950 dark:text-blue-300",
  slate: "bg-slate-200 text-slate-600 dark:bg-slate-800 dark:text-slate-400",
};

export function Badge({
  children,
  tone = "neutral",
}: {
  children: ReactNode;
  tone?: keyof typeof TONES | string;
}) {
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${
        TONES[tone] ?? TONES.neutral
      }`}
    >
      {children}
    </span>
  );
}

/** Map a member status to a badge tone. */
export function statusTone(status: string): string {
  switch (status) {
    case "active":
      return "green";
    case "invited":
    case "pending":
      return "amber";
    case "suspended":
    case "disabled":
      return "slate";
    default:
      return "neutral";
  }
}

/** Map a role to a badge tone. */
export function roleTone(role: string): string {
  return role === "owner" || role === "admin" ? "blue" : "neutral";
}

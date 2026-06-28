"use client";

import { type InputHTMLAttributes, type ReactNode } from "react";

/** Labeled text input. */
export function Field({
  label,
  hint,
  ...props
}: { label: string; hint?: string } & InputHTMLAttributes<HTMLInputElement>) {
  return (
    <label className="block">
      <span className="mb-1 block text-sm font-medium text-slate-700 dark:text-slate-300">
        {label}
      </span>
      <input
        className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm
          text-slate-900 outline-none ring-blue-500 focus:ring-2
          dark:border-slate-700 dark:bg-slate-950 dark:text-slate-100"
        {...props}
      />
      {hint ? (
        <span className="mt-1 block text-xs text-slate-500 dark:text-slate-400">
          {hint}
        </span>
      ) : null}
    </label>
  );
}

/** Primary submit button with a busy state. */
export function Button({
  children,
  busy = false,
  variant = "primary",
  ...props
}: {
  children: ReactNode;
  busy?: boolean;
  variant?: "primary" | "ghost" | "danger";
} & React.ButtonHTMLAttributes<HTMLButtonElement>) {
  const base =
    "inline-flex items-center justify-center gap-2 rounded-lg px-4 py-2 text-sm " +
    "font-medium transition disabled:opacity-60";
  const styles = {
    primary: "bg-blue-600 text-white hover:bg-blue-700",
    ghost:
      "border border-slate-300 text-slate-700 hover:bg-slate-100 " +
      "dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-800",
    danger: "border border-red-300 text-red-700 hover:bg-red-50",
  }[variant];
  return (
    <button className={`${base} ${styles}`} disabled={busy || props.disabled} {...props}>
      {busy ? "Working…" : children}
    </button>
  );
}

/** Inline error banner for forms / panels. */
export function ErrorNote({ children }: { children: ReactNode }) {
  if (!children) return null;
  return (
    <p
      role="alert"
      className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm
        text-red-700 dark:border-red-900 dark:bg-red-950 dark:text-red-300"
    >
      {children}
    </p>
  );
}

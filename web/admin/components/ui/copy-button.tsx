"use client";

import { useState } from "react";

/** Copy-to-clipboard button with a brief "Copied" confirmation. */
export function CopyButton({ value, label = "Copy" }: { value: string; label?: string }) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // clipboard unavailable (e.g. non-secure context) — no-op
    }
  }

  return (
    <button
      type="button"
      onClick={copy}
      className="shrink-0 rounded-lg border border-slate-300 px-2.5 py-1 text-xs font-medium
        text-slate-700 hover:bg-slate-100
        dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-800"
    >
      {copied ? "Copied" : label}
    </button>
  );
}

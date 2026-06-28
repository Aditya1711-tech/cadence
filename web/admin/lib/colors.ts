// Category color palette as hex — the single source of truth for inline SVG /
// style fills (the team heatmap). Mirrors the `cat.*` colors in
// tailwind.config.ts and the personal dashboard so the two products stay in
// lockstep.

import type { Category } from "@/lib/contract/types";

export const CATEGORY_COLOR: Record<Category, string> = {
  deep_work: "#2563eb",
  meetings: "#d97706",
  comms: "#db2777",
  research: "#7c3aed",
  code_review: "#059669",
  ai_assisted: "#0891b2",
  idle: "#94a3b8",
  other: "#64748b",
};

const KNOWN = new Set(Object.keys(CATEGORY_COLOR));

/** Color for a category string, with a sane fallback for null/unknown. */
export function colorFor(category: string | null | undefined): string {
  if (category && KNOWN.has(category)) {
    return CATEGORY_COLOR[category as Category];
  }
  return CATEGORY_COLOR.other;
}

/** Human-friendly label for a category key. */
export function categoryLabel(category: string): string {
  return category
    .split("_")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(" ");
}

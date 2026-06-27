// Category color palette as hex — the single source of truth for SVG fills
// (timeline ribbon, donut). Mirrors the `cat.*` colors in tailwind.config.ts so
// the Tailwind-classed UI and the hand-drawn SVG stay in lockstep.

import type { Category } from "@/lib/contract/event";

export const CATEGORY_COLOR: Record<Category, string> = {
  deep_work: "#2563eb",
  meetings: "#d97706",
  comms: "#db2777",
  research: "#7c3aed",
  code_review: "#059669",
  ai_assisted: "#0891b2",
  idle: "#cbd5e1", // lighter slate so idle reads as a gap, not work
  other: "#64748b",
};

/** Color for an event's category, with a sane fallback for null/unknown. */
export function colorFor(category: Category | null): string {
  return category ? CATEGORY_COLOR[category] : CATEGORY_COLOR.other;
}

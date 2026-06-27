import type { Config } from "tailwindcss";

// Category colors are the single source of truth shared by the timeline ribbon
// and the category donut so the two visualizations read as one picture (P1-D.1).
const config: Config = {
  content: [
    "./app/**/*.{ts,tsx}",
    "./components/**/*.{ts,tsx}",
    "./lib/**/*.{ts,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        // Category palette — keyed to the frozen v1 category enum.
        cat: {
          deep_work: "#2563eb", // blue-600
          meetings: "#d97706", // amber-600
          comms: "#db2777", // pink-600
          research: "#7c3aed", // violet-600
          code_review: "#059669", // emerald-600
          ai_assisted: "#0891b2", // cyan-600
          idle: "#94a3b8", // slate-400
          other: "#64748b", // slate-500
        },
      },
    },
  },
  plugins: [],
};

export default config;

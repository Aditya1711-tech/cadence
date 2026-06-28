import type { Config } from "tailwindcss";

// Category palette is the single source of truth shared with the personal
// dashboard (web/dashboard/tailwind.config.ts) so the admin heatmap and the
// personal donut read as one product. Keyed to the frozen v1 category enum.
const config: Config = {
  content: [
    "./app/**/*.{ts,tsx}",
    "./components/**/*.{ts,tsx}",
    "./lib/**/*.{ts,tsx}",
  ],
  theme: {
    extend: {
      colors: {
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

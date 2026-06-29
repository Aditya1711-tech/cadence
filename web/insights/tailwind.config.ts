import type { Config } from "tailwindcss";

// Category palette mirrors web/admin + web/dashboard so the whole product reads
// as one. Keyed to the frozen v1 category enum (00-SYSTEM-KNOWLEDGE §5).
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
          deep_work: "#2563eb",
          meetings: "#d97706",
          comms: "#db2777",
          research: "#7c3aed",
          code_review: "#059669",
          ai_assisted: "#0891b2",
          idle: "#94a3b8",
          other: "#64748b",
        },
      },
    },
  },
  plugins: [],
};

export default config;

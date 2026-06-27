// Factory: pick the live daemon client or the mock based on environment.
//
// This runs SERVER-SIDE only (the /api/timeline route handler and the page's
// initial read), so it uses RUNTIME, non-public env vars — NOT NEXT_PUBLIC_*,
// which Next inlines at build time and so can't be changed per deployment:
//
//   CADENCE_AGENT_BASE   base URL of the local daemon (default below)
//   CADENCE_USE_MOCK     "1"/"true" serves the fixture day instead of the daemon
//
// The browser never talks to the daemon directly (it polls our same-origin
// proxy), so no public/build-time variable is needed. We accept the
// phase-doc-named NEXT_PUBLIC_CADENCE_AGENT_BASE only as a last-resort fallback.

import type { AgentClient } from "@/lib/agent/client";
import { HttpAgentClient } from "@/lib/agent/http";
import { MockAgentClient } from "@/lib/agent/mock";

// The daemon's default loopback port (P1-A: cmd/cadence-agent defaultPort).
const DEFAULT_AGENT_BASE = "http://127.0.0.1:47821";

export function getAgentClient(): AgentClient {
  const useMock = /^(1|true|yes)$/i.test(process.env.CADENCE_USE_MOCK ?? "");
  if (useMock) {
    return new MockAgentClient();
  }
  const base =
    process.env.CADENCE_AGENT_BASE?.trim() ||
    process.env.NEXT_PUBLIC_CADENCE_AGENT_BASE?.trim() ||
    DEFAULT_AGENT_BASE;
  return new HttpAgentClient(base);
}

export type { AgentClient };
export { AgentOfflineError } from "@/lib/agent/client";

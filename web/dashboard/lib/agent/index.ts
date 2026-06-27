// Factory: pick the live daemon client or the mock based on environment.
//
// - NEXT_PUBLIC_CADENCE_AGENT_BASE  e.g. http://127.0.0.1:7676 (the daemon)
// - NEXT_PUBLIC_CADENCE_USE_MOCK    "1"/"true" forces the fixture client
//
// When the base URL is unset we fall back to the mock so the dashboard renders
// during development without a running daemon. Resolved server-side (in the
// route handler), which keeps daemon access off the browser and sidesteps the
// CORS question P1-A still has to answer.

import type { AgentClient } from "@/lib/agent/client";
import { HttpAgentClient } from "@/lib/agent/http";
import { MockAgentClient } from "@/lib/agent/mock";

export function getAgentClient(): AgentClient {
  const base = process.env.NEXT_PUBLIC_CADENCE_AGENT_BASE?.trim();
  const useMock = /^(1|true|yes)$/i.test(
    process.env.NEXT_PUBLIC_CADENCE_USE_MOCK ?? "",
  );

  if (!useMock && base) {
    return new HttpAgentClient(base);
  }
  return new MockAgentClient();
}

export type { AgentClient };
export { AgentOfflineError } from "@/lib/agent/client";

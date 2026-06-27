// Local read route, proxied through Next (same-origin for the browser, so no
// CORS is needed against the loopback daemon). Client components poll this for
// live refresh / offline-retry (P1-D.7). Like the daemon (P1-A.5), it returns a
// bare JSON array of events and uses RFC 7807 problem+json for errors (§6).

import { NextRequest, NextResponse } from "next/server";
import { getAgentClient, AgentOfflineError } from "@/lib/agent";

export const dynamic = "force-dynamic";

function problem(status: number, title: string, detail: string) {
  return NextResponse.json(
    { type: "about:blank", title, status, detail },
    { status, headers: { "content-type": "application/problem+json" } },
  );
}

export async function GET(req: NextRequest) {
  const { searchParams } = req.nextUrl;
  const fromRaw = searchParams.get("from");
  const toRaw = searchParams.get("to");
  if (!fromRaw || !toRaw) {
    return problem(400, "Missing range", "both `from` and `to` are required");
  }
  const from = new Date(fromRaw);
  const to = new Date(toRaw);
  if (Number.isNaN(from.getTime()) || Number.isNaN(to.getTime())) {
    return problem(400, "Bad range", "`from`/`to` must be RFC3339 timestamps");
  }

  try {
    const events = await getAgentClient().getTimeline(from, to);
    return NextResponse.json(events);
  } catch (err) {
    if (err instanceof AgentOfflineError) {
      return problem(503, "Daemon offline", err.message);
    }
    return problem(
      502,
      "Read failed",
      err instanceof Error ? err.message : "unknown error",
    );
  }
}

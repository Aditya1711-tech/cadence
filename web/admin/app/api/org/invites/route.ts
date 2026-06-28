import { NextRequest } from "next/server";
import { proxyJson } from "@/lib/api/proxy";
import { problem } from "@/lib/api/problem";

export const dynamic = "force-dynamic";

/** POST /org/invites — admin creates a targeted or open invite. */
export async function POST(req: NextRequest) {
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return problem(400, "Bad request", "expected a JSON body");
  }
  return proxyJson("/api/v1/org/invites", "POST", body);
}

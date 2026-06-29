import { NextRequest } from "next/server";
import { proxyJson } from "@/lib/api/proxy";
import { problem } from "@/lib/api/problem";

export const dynamic = "force-dynamic";

// BFF proxy → POST /api/v1/query/nl (admin-only, gated server-side). The browser
// never holds a token; the backend validates the generated SQL and executes it
// only via the cadence_readonly role.
export async function POST(req: NextRequest) {
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return problem(400, "Bad request", "expected a JSON body");
  }
  return proxyJson("/api/v1/query/nl", "POST", body);
}

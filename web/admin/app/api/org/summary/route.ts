import { NextRequest } from "next/server";
import { proxyGet } from "@/lib/api/proxy";

export const dynamic = "force-dynamic";

/** GET /org/summary — privacy-aware team rollup (range + team passthrough). */
export async function GET(req: NextRequest) {
  const qs = req.nextUrl.search; // preserves ?range=&team=
  return proxyGet(`/api/v1/org/summary${qs}`);
}

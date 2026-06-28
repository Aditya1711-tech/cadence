import { NextRequest } from "next/server";
import { proxyGet } from "@/lib/api/proxy";

export const dynamic = "force-dynamic";

/** GET /org/members — paginated roster (cursor + limit passthrough). */
export async function GET(req: NextRequest) {
  const qs = req.nextUrl.search; // preserves ?cursor=&limit=
  return proxyGet(`/api/v1/org/members${qs}`);
}

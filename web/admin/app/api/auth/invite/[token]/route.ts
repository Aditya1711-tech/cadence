import { NextResponse } from "next/server";
import { rawBackend } from "@/lib/api/backend";
import { passthroughError } from "@/lib/api/problem";

export const dynamic = "force-dynamic";

/** Preview an invite (org name + targeted email) for the accept page. */
export async function GET(
  _req: Request,
  { params }: { params: { token: string } },
) {
  const res = await rawBackend(
    `/api/v1/auth/invite/${encodeURIComponent(params.token)}`,
  );
  if (!res.ok) return passthroughError(res);
  return NextResponse.json(await res.json());
}

import { NextResponse } from "next/server";
import { readSession } from "@/lib/api/session";
import { problem } from "@/lib/api/problem";

export const dynamic = "force-dynamic";

/** Current identity for the authenticated shell. */
export async function GET() {
  const s = readSession();
  if (!s) return problem(401, "Unauthorized", "no active session");
  return NextResponse.json({ member: s.member, org: s.org });
}

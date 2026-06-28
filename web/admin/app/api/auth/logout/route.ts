import { NextResponse } from "next/server";
import { rawBackend } from "@/lib/api/backend";
import { clearSession, readSession } from "@/lib/api/session";

export const dynamic = "force-dynamic";

export async function POST() {
  const s = readSession();
  // Best-effort backend revoke; clear the local cookie regardless of outcome.
  if (s) {
    try {
      await rawBackend("/api/v1/auth/logout", {
        method: "POST",
        body: JSON.stringify({ refresh_token: s.refreshToken }),
      });
    } catch {
      // ignore — we still clear the session below
    }
  }
  clearSession();
  return new NextResponse(null, { status: 204 });
}

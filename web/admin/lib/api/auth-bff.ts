// Shared BFF helper: turn a backend auth Response into a session cookie + a
// browser-safe identity payload (NO tokens leave the server).

import { NextResponse } from "next/server";
import type { AuthResponse } from "@/lib/contract/types";
import { passthroughError } from "@/lib/api/problem";
import { sessionFromAuth, writeSession } from "@/lib/api/session";

/** On 2xx: set the session cookie and return {member, org}. Else: passthrough. */
export async function completeAuth(res: Response): Promise<NextResponse> {
  if (!res.ok) return passthroughError(res);
  const auth = (await res.json()) as AuthResponse;
  writeSession(sessionFromAuth(auth));
  return NextResponse.json({ member: auth.member, org: auth.org });
}

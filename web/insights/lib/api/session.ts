// The session — token pair plus an identity snapshot — persisted in a single
// httpOnly cookie. The browser never sees the tokens; only the BFF route
// handlers read/rotate them server-side.

import { cookies } from "next/headers";
import type { AuthResponse, MemberView, OrgView } from "@/lib/contract/types";
import { cookieName, cookieSecure } from "@/lib/api/config";

export interface Session {
  accessToken: string;
  refreshToken: string;
  member: MemberView;
  org: OrgView;
}

const MAX_AGE_SECONDS = 60 * 60 * 24 * 30; // 30d; refresh-token lifetime bounds real validity

function encode(s: Session): string {
  return Buffer.from(JSON.stringify(s), "utf8").toString("base64url");
}

function decode(raw: string): Session | null {
  try {
    const s = JSON.parse(Buffer.from(raw, "base64url").toString("utf8")) as Session;
    if (s?.accessToken && s?.refreshToken && s?.member && s?.org) return s;
    return null;
  } catch {
    return null;
  }
}

/** Build a Session from a backend AuthResponse (login). */
export function sessionFromAuth(a: AuthResponse): Session {
  return {
    accessToken: a.access_token,
    refreshToken: a.refresh_token,
    member: a.member,
    org: a.org,
  };
}

/** Current session, or null if unauthenticated / malformed cookie. */
export function readSession(): Session | null {
  const raw = cookies().get(cookieName())?.value;
  return raw ? decode(raw) : null;
}

/** Persist the session (set the httpOnly cookie). Route Handlers / Actions only. */
export function writeSession(s: Session): void {
  cookies().set(cookieName(), encode(s), {
    httpOnly: true,
    sameSite: "lax",
    secure: cookieSecure(),
    path: "/",
    maxAge: MAX_AGE_SECONDS,
  });
}

/** Clear the session cookie (logout / refresh failure). */
export function clearSession(): void {
  cookies().set(cookieName(), "", {
    httpOnly: true,
    sameSite: "lax",
    secure: cookieSecure(),
    path: "/",
    maxAge: 0,
  });
}

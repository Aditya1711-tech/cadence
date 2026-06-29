// Server-side client for the Spring backend (P2-A). All backend traffic flows
// through here, inside BFF route handlers — the one place that owns the access
// token, the Bearer header, and the invisible refresh-on-401 + cookie rotation.

import type { TokenPair } from "@/lib/contract/types";
import { apiBase } from "@/lib/api/config";
import {
  clearSession,
  readSession,
  writeSession,
  type Session,
} from "@/lib/api/session";

/** Thrown when there is no session, or the refresh token is dead. */
export class Unauthenticated extends Error {
  constructor(message = "not authenticated") {
    super(message);
    this.name = "Unauthenticated";
  }
}

/** Unauthenticated call to the backend (login). */
export function rawBackend(path: string, init?: RequestInit): Promise<Response> {
  return fetch(apiBase() + path, {
    ...init,
    cache: "no-store",
    headers: {
      Accept: "application/json",
      ...(init?.body ? { "content-type": "application/json" } : {}),
      ...(init?.headers ?? {}),
    },
  });
}

function withBearer(init: RequestInit | undefined, token: string): RequestInit {
  return {
    ...init,
    cache: "no-store",
    headers: {
      Accept: "application/json",
      ...(init?.body ? { "content-type": "application/json" } : {}),
      ...(init?.headers ?? {}),
      Authorization: `Bearer ${token}`,
    },
  };
}

/** Exchange the refresh token for a new pair; rotate the session cookie. */
async function tryRefresh(s: Session): Promise<string | null> {
  const res = await rawBackend("/api/v1/auth/refresh", {
    method: "POST",
    body: JSON.stringify({ refresh_token: s.refreshToken }),
  });
  if (!res.ok) return null;
  const pair = (await res.json()) as TokenPair;
  writeSession({
    ...s,
    accessToken: pair.access_token,
    refreshToken: pair.refresh_token,
  });
  return pair.access_token;
}

/**
 * Authenticated backend call. Attaches the current access token; on a 401 it
 * transparently refreshes once and retries. Throws {@link Unauthenticated} when
 * there is no session or the refresh fails (the cookie is then cleared).
 */
export async function authedFetch(path: string, init?: RequestInit): Promise<Response> {
  const s = readSession();
  if (!s) throw new Unauthenticated();

  let res = await fetch(apiBase() + path, withBearer(init, s.accessToken));
  if (res.status !== 401) return res;

  const fresh = await tryRefresh(s);
  if (!fresh) {
    clearSession();
    throw new Unauthenticated("session expired");
  }
  res = await fetch(apiBase() + path, withBearer(init, fresh));
  return res;
}

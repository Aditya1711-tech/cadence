// Server-side runtime config for the BFF. Read on the server only — never
// inlined into the browser bundle (that is why these are plain env vars, not
// NEXT_PUBLIC_*; see .env.example and the P2-E env note in PROGRESS.md).

/** Base URL of the Spring backend (P2-A). */
export function apiBase(): string {
  return process.env.CADENCE_API_BASE?.replace(/\/+$/, "") || "http://localhost:8080";
}

/** Name of the httpOnly session cookie that holds the token pair + identity. */
export function cookieName(): string {
  return process.env.CADENCE_ADMIN_COOKIE || "cadence_admin_session";
}

/** Mark the session cookie Secure (only when served over HTTPS). */
export function cookieSecure(): boolean {
  return process.env.CADENCE_COOKIE_SECURE === "true";
}

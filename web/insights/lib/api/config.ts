// Server-side runtime config for the BFF. Read on the server only — never
// inlined into the browser bundle (plain env vars, not NEXT_PUBLIC_*).

/** Base URL of the Spring backend (P2-A). */
export function apiBase(): string {
  return process.env.CADENCE_API_BASE?.replace(/\/+$/, "") || "http://localhost:8080";
}

/** Name of the httpOnly session cookie that holds the token pair + identity. */
export function cookieName(): string {
  return process.env.CADENCE_INSIGHTS_COOKIE || "cadence_insights_session";
}

/** Mark the session cookie Secure (only when served over HTTPS). */
export function cookieSecure(): boolean {
  return process.env.CADENCE_COOKIE_SECURE === "true";
}

// URL helpers. Per P1-C.2 the collector works from the domain, not the full
// path — this is where we extract it and decide what is even trackable.

/** Schemes/pages we never track: browser-internal, local files, the extension
 *  itself, blank/new-tab. These are noise, not "work", and some can't be read. */
const UNTRACKABLE_SCHEMES = new Set([
  "chrome:",
  "chrome-extension:",
  "chrome-untrusted:",
  "edge:",
  "about:",
  "file:",
  "data:",
  "view-source:",
  "devtools:",
]);

/**
 * Returns the hostname for a trackable http(s) URL, or null if the URL is
 * missing, malformed, or one of the untrackable schemes above. The hostname is
 * the unit we classify and (in domain_only mode) the only locator we keep.
 */
export function extractDomain(rawUrl: string | undefined | null): string | null {
  if (!rawUrl) return null;
  let parsed: URL;
  try {
    parsed = new URL(rawUrl);
  } catch {
    return null;
  }
  if (UNTRACKABLE_SCHEMES.has(parsed.protocol)) return null;
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") return null;
  const host = parsed.hostname.trim();
  return host.length > 0 ? host : null;
}

/** True if the URL is something we should open a focus session for. */
export function isTrackableUrl(rawUrl: string | undefined | null): boolean {
  return extractDomain(rawUrl) !== null;
}

/**
 * Origin-only form of a URL ("https://github.com"), used as the default `url`
 * value under domain_only privacy (P1-C.2). Returns null for untrackable URLs.
 */
export function originOnly(rawUrl: string | undefined | null): string | null {
  if (!rawUrl) return null;
  try {
    return new URL(rawUrl).origin;
  } catch {
    return null;
  }
}

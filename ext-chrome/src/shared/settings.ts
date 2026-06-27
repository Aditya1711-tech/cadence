// User-facing settings, persisted in chrome.storage.local. The popup (P1-C.6)
// reads/writes these; the collector reads them when emitting. Defaults match the
// daemon: port 47821 (agent main.go) and the privacy-first domain_only policy.

export type UrlPrivacy = "domain_only" | "full";

/** Default loopback port the agent binds (agent cmd/cadence-agent main.go). */
export const DEFAULT_AGENT_PORT = 47821;
/** Privacy-first default: send origin only, drop titles (P1-C.2). */
export const DEFAULT_URL_PRIVACY: UrlPrivacy = "domain_only";

export interface Settings {
  /** Loopback port the daemon listens on; must match CADENCE_AGENT_PORT. */
  agentPort: number;
  /** domain_only (default) sends origin-only url + null title; full sends both. */
  urlPrivacy: UrlPrivacy;
  /** When true, the collector stops tracking (popup pause button). */
  paused: boolean;
}

/** chrome.storage.local key for settings — exported so the SW can watch it. */
export const SETTINGS_KEY = "cadence.settings";

const DEFAULTS: Settings = {
  agentPort: DEFAULT_AGENT_PORT,
  urlPrivacy: DEFAULT_URL_PRIVACY,
  paused: false,
};

/** Reads settings, filling any missing field with its default. */
export async function getSettings(): Promise<Settings> {
  const got = await chrome.storage.local.get(SETTINGS_KEY);
  const stored = got[SETTINGS_KEY] as Partial<Settings> | undefined;
  return { ...DEFAULTS, ...stored };
}

/** Merges a partial update into the stored settings and returns the result. */
export async function setSettings(patch: Partial<Settings>): Promise<Settings> {
  const next = { ...(await getSettings()), ...patch };
  await chrome.storage.local.set({ [SETTINGS_KEY]: next });
  return next;
}

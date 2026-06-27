// member_id provisioning.
//
// The Event Contract requires a non-empty member_id identifying the local member.
// Ideally every collector shares ONE install-time identity issued by the daemon,
// but P1-A.5 does not yet expose member_id via the local API (see the OPEN NEEDS
// line in PROGRESS.md). As an interim, the Chrome collector self-generates a
// stable uuid and persists it; when the daemon exposes a shared id, this should
// fetch and adopt it instead.

const MEMBER_ID_KEY = "cadence.memberId";

/** Returns the stable local member_id, generating and persisting one on first use. */
export async function getMemberId(): Promise<string> {
  const got = await chrome.storage.local.get(MEMBER_ID_KEY);
  const existing = got[MEMBER_ID_KEY] as string | undefined;
  if (existing) return existing;
  const id = crypto.randomUUID();
  await chrome.storage.local.set({ [MEMBER_ID_KEY]: id });
  return id;
}

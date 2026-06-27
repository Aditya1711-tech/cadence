// Local member identity (P1-B.4, provisional).
//
// The Event Contract requires a non-empty `member_id` ("local member identity,
// set at install"), and the daemon validates it but does not yet expose a
// canonical device identity nor stamp it on ingest (see the NEEDS line to P1-A
// in docs/PROGRESS.md). For Phase 1 local dogfooding we therefore generate a
// stable id once and persist it in the extension's globalState.
//
// This is intentionally swappable: when P1-A ships an identity route (or stamps
// member_id on ingest), resolveMemberId should prefer that source. Until then a
// per-install uuid is sufficient for the local store + dashboard.

import { randomUUID } from 'crypto';

export const MEMBER_ID_KEY = 'cadence.memberId';

/** Minimal slice of vscode.Memento we depend on (keeps this unit-testable). */
export interface MemberIdStore {
  get(key: string): string | undefined;
  update(key: string, value: string): Thenable<void> | Promise<void> | void;
}

/**
 * Return the persisted member id, generating and storing one on first use.
 * `make` is injectable for deterministic tests.
 */
export function resolveMemberId(store: MemberIdStore, make: () => string = randomUUID): string {
  const existing = store.get(MEMBER_ID_KEY);
  if (existing && existing.trim() !== '') {
    return existing;
  }
  const id = make();
  void store.update(MEMBER_ID_KEY, id);
  return id;
}

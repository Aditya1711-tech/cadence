# P2-B.2 — Device enrollment (findings)

Goal: how a freshly-installed daemon learns its `member_id` + auth tokens
**without a painful login**, where those secrets live, and how they refresh.
Maps to phase tasks P2-B.4 (keychain token storage + refresh) and P2-B.5
(enrollment via invite link / code).

> Verified against the as-built P2-A auth surface: `AuthController`,
> `OnboardingController`, `AuthService.{mintDeviceCode,enrollDevice}`,
> `AuthDtos`, and the RESOLVED coordination line P2-A → P2-B in `PROGRESS.md`.

---

## Summary recommendation

- **Short-code device enrollment, no password on the device.** The member logs
  in once *in the web app*, mints a one-time device code, and pastes it into the
  daemon (`cadence-agent enroll <code>`). The daemon exchanges it for its
  identity + tokens. The member's password never touches the device. This is
  the smoothest "invisible" path and exactly what P2-A built.
- **Adopt the server's `member_id`.** Enrollment returns the canonical
  `member_id = members.id`. The daemon persists it and all collectors on the
  machine share it (resolves the P1 member-id gap noted in coordination).
  Ingest stamps `member_id` from the JWT anyway, so this is for local
  correlation/consistency, not ingest correctness.
- **Secrets live in the OS keychain**, reusing the existing `keyring.Keyring`
  interface (same store the master encryption key already uses). Nothing
  sensitive is written to disk in plaintext.

---

## 1. The enrollment journey (as built by P2-A)

```
1. Admin invites member by email           POST /api/v1/auth/invite/* (admin)
2. Member accepts in the web app           POST /api/v1/auth/invite/accept -> logged in
3. Member mints a device code (web/CLI)    POST /api/v1/me/device-codes  (auth)
                                            -> { code, expiresAt }   (15-min TTL, one-time)
4. Member pastes code into the daemon       cadence-agent enroll <code>
5. Daemon exchanges the code                POST /api/v1/auth/device/enroll { code }
                                            -> { memberId, accessToken, refreshToken,
                                                 tokenType, expiresInSeconds }
6. Daemon stores member_id + tokens in the OS keychain; sync can now run.
```

- The "invite link" in the phase title resolves, in the as-built backend, to the
  **device code** minted at step 3 — that is the artifact the daemon consumes.
  The daemon never handles the email invite or a password; it only ever sees the
  short-lived enrollment code.
- The code is one-time (`one_time_tokens`, kind `device_enroll`, marked
  `used_at` on redemption) and expires in 15 minutes, so a leaked code has a
  tiny blast radius.

## 2. What the daemon stores, and where

Reuse `keyring.Keyring` (already the OS keychain abstraction; `OS{}` in prod,
in-memory fake in tests). Under the existing service name
(`com.cadence.agent`, overridable via `CADENCE_KEYCHAIN_SERVICE`), add accounts:

| account            | value                                  |
|--------------------|----------------------------------------|
| `member-id`        | canonical `member_id` from enrollment  |
| `access-token`     | current access JWT (HS256, ~60m)       |
| `refresh-token`    | current opaque refresh token (rotating)|
| `cloud-base`       | resolved backend base URL (optional; else env) |

- `member-id` already has a keychain account (`loadOrCreateMemberID` in
  `main.go`). Enrollment **overwrites** the self-generated uuid with the
  canonical server id, so post-enroll all collectors converge on one identity.
  (Pre-enroll behavior is unchanged — the daemon still works fully offline.)
- Tokens in the keychain, never on disk, never logged. The access token is
  short-lived; the refresh token is the sensitive long-lived secret.

## 3. Refresh flow (P2-B.4)

- Access JWT lasts ~60 min (`expiresInSeconds` from enroll/refresh). Rather than
  pre-expiring against a possibly-skewed local clock, the sync loop is
  **reactive**: on a `401` it calls
  `POST /api/v1/auth/refresh { refresh_token }` → `{ accessToken, refreshToken,
  tokenType, expiresInSeconds }`, **rotating** — it stores the *new* refresh
  token and retries the request once.
- **Rotation reuse is fatal by design.** P2-A's refresh is single-use; reusing
  an old refresh token revokes the whole family. So the daemon must persist the
  rotated refresh token transactionally *before* the next use, and must never
  run two refreshes concurrently. The sync loop is single-goroutine, which makes
  this easy: serialize refresh, persist new token, then proceed.
- If refresh itself returns 401/expired (member removed, family revoked, device
  de-authorized), sync stops and surfaces a **re-enroll required** state
  (logged, and exposed for a future UI). No retry storm.

## 4. Edge cases

- **Not yet enrolled:** sync loop is a no-op (logs once at debug). The daemon is
  fully functional locally without ever enrolling — enrollment only turns on
  cloud sync.
- **Re-enroll on an already-enrolled device:** overwrite member-id + tokens.
  Because ingest is idempotent on `event_id`, any events synced under the old
  identity are harmless; new events stamp the (same) member from the JWT.
- **Code expired / already used:** enroll returns an error (problem+json); the
  CLI prints a friendly "code expired — mint a new one in the web app."
- **Keychain unavailable (headless Linux without Secret Service):** same
  constraint the master key already has; document the `CADENCE_*` fallback and
  fail loudly rather than persisting secrets to disk.

## 5. CLI surface (proposed, within `cmd/cadence-agent` glue)

```
cadence-agent enroll <code>     # one-shot: exchange code, persist identity+tokens, exit
cadence-agent status            # prints enrolled? member_id (masked), last sync, backlog
```

`enroll`/`status` are thin commands that call into `/agent/sync`. The daemon's
normal run path additionally starts the sync loop when enrolled. (Wiring in
`main.go` is the one P1-A-owned file P2-B must touch for the entrypoint; keep it
to a minimal call into the sync package — flagged in coordination if needed.)
</content>

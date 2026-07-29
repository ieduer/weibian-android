# Security review — v1.1.0 direct candidate

Date: 2026-07-29
Scope: Android Direct client, `weibian-content` Worker, D1 rankings, content and
update contracts, plus the task-owned immutable R2 content prefix. Shared User
Center, APIS, Pulse and portal implementations were treated as external
dependencies and were not modified.

## Outcome

No unresolved critical or high-severity issue was found in the owned App and
Worker scope after the changes below. This is a code and live-contract review,
not a replacement for the remaining physical-tablet, feedback-delivery and
shared-registration production gates.

## Fixed in this candidate

| Severity | Finding | Resolution | Verification |
|---|---|---|---|
| High | Ranking identity hashing did not explicitly reject a missing or weak server pepper | A minimum 32-character server-only value is now required before any authenticated ranking sync | Worker unit test plus production unauthenticated ranking probe |
| Medium | Generic JSON responses could be read into memory without a byte limit | All App JSON responses are capped at 2 MiB before parsing | Android tests, lint and build |
| Medium | The outer progress-sync failure path could retry without the documented attempt bound | Both per-item and outer failures now stop after the same maximum attempt count | Source review, lint and build |
| Medium | Cleartext transport policy depended on platform defaults | `android:usesCleartextTraffic="false"` is explicit | Merged manifest and lint |
| Medium | Public rights wording contradicted the owner-authorized release scope | Landing page and repository documentation now point to the rights receipt | Production landing-page readback |
| Medium | A Worker-bundled “immutable” route could no longer serve an older content version after a new deployment | Authorized bundles now live in non-overwritten R2 hash paths; an exact code allowlist exposes reviewed versions | Public byte/size/hash parity and unknown-version 404 |

## Verified controls

- The App contains no WebView and no model, Cloudflare, Telegram or ranking
  secret.
- Login is restricted to the fixed first-party HTTPS User Center origin.
  Passwords are used only for the user-initiated request and are not persisted.
- The returned session cookie is encrypted using an Android Keystore AES-GCM
  key and excluded from backup and device transfer.
- Content, delta and update URLs require exact first-party HTTPS hosts and
  immutable path shapes. Hash, size, schema and identity fields are validated
  before activation.
- A checked-in metadata lock contains no corpus bytes and lets clean CI fetch
  the exact authorized immutable object with a bounded streamed download.
- Downloaded content uses staged/active/previous slots and atomic rename; a
  partial staged file never becomes active.
- The ranking endpoint derives points from User Center progress through a
  service binding. The client cannot submit its own score.
- D1 stores only a keyed pseudonymous identifier and bounded aggregate
  progress. SQL values use prepared statements.
- Ranking responses and authenticated endpoints are `no-store`; immutable
  content is hash-addressed.
- Gitleaks scanned the working tree with redaction enabled and found no secret.
- Worker tests, JavaScript syntax checks, 52 Android unit tests, lint and release
  assembly passed.
- The final v1.1.0 candidate was rebuilt from the dedicated
  `/Users/ylsuen/.android/weibian-release.env` authority and matches the
  accepted v1.0.0 certificate SHA-256. An earlier task-local build made with a
  generic Companion keystore was rejected by the continuity gate, cleaned and
  never uploaded.

## Accepted or external residual risk

- The temporary native credential adapter is accepted in
  `IDENTITY_ADR.md`. It must be replaced when User Center exposes a registered
  standalone-App browser handoff; Companion credentials or client identity must
  not be reused.
- TLS certificate pinning is intentionally not used. The fixed HTTPS origins
  rely on Android's system trust store so normal certificate rotation does not
  brick login or updates.
- User Center registration is not live, and feedback has not completed the
  physical App → User Center → aggregate D1 → Telegram receipt chain. These are
  release-support blockers even though the leaf code path exists.
- No physical tablet is currently available. Emulator evidence cannot close the
  adaptive-window and accessibility gate.
- A real content delta is not published because the public v1.0.0 content hash
  and v1.1.0 candidate content hash are identical. The first future content
  change must exercise delta reconstruction, full-bundle fallback and previous
  rollback before its pointer moves.

## Recheck triggers

Repeat this review before changing identity, User Center payloads, update
manifest schema, content-delta schema, D1 ranking fields, Worker bindings,
network hosts, Android exported components, Room schema or release signing.

# Security review — v1.1.1 / code 3 direct candidate

Date: 2026-07-29
Scope: Android Direct client, `weibian-content` Worker, D1 rankings, content and
update contracts, plus the task-owned immutable R2 content prefix. Shared User
Center, APIS, Pulse and portal implementations were treated as external
dependencies and were not modified.

## Outcome

No unresolved critical or high-severity issue was found in the owned App and
Worker scope after the changes below. This is a code and live-contract review,
not a release acceptance. Physical feedback, offline/recovery, differing
content, tablet, User Center registry canary and final release/self-update
remain open.

## Fixed in this candidate

| Severity | Finding | Resolution | Verification |
|---|---|---|---|
| High | Ranking identity hashing did not explicitly reject a missing or weak server pepper | A minimum 32-character server-only value is now required before any authenticated ranking sync | Worker unit test plus production unauthenticated ranking probe |
| Medium | Generic JSON responses could be read into memory without a byte limit | All App JSON responses are capped at 2 MiB before parsing | Android tests, lint and build |
| Medium | The outer progress-sync failure path could retry without the documented attempt bound | Both per-item and outer failures now stop after the same maximum attempt count | Source review, lint and build |
| Medium | Cleartext transport policy depended on platform defaults | `android:usesCleartextTraffic="false"` is explicit | Merged manifest and lint |
| Medium | Public rights wording contradicted the owner-authorized release scope | Landing page and repository documentation now point to the rights receipt | Production landing-page readback |
| Medium | A Worker-bundled “immutable” route could no longer serve an older content version after a new deployment | Authorized bundles now live in non-overwritten R2 hash paths; an exact code allowlist exposes reviewed versions | Public byte/size/hash parity and unknown-version 404 |
| Medium | Profile favorites and notes reused the same `chapterId` key in one `LazyColumn`, so the same chapter in both sections crashed the main thread | Keys are now section-namespaced (`favorite:*`, `note:*`, `achievement:*`) | Regression unit tests plus physical OnePlus code 3 full-profile scrolling with no scoped App fatal |
| Medium | Localized feedback labels did not match the User Center wire enum and could collapse to the wrong category | UI labels now map to the approved wire codes and receipts require stored/notification fields | Unit tests and source review; physical delivery chain remains open |

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
- Worker tests, JavaScript syntax checks, 55 Android unit tests and lint/build
  passed at code checkpoint
  `0f2dae26ee84b676a401cad70fa088a8fd8eaac6`. GitHub Actions run
  `30464811968`, attempt 2, is green.
- The physical OnePlus code 3 profile regression and AI讲解／non-sensitive
  高考批改 paths passed without exposing credentials or student content.
- The only release signing authority is
  `/Users/ylsuen/.android/weibian-release.env`, and its signer matches the
  accepted v1.0.0 certificate SHA-256. A clean-signer build exists only as an
  interim artifact: documentation changes alter release bytes, so final
  hash/size are intentionally not recorded here. The final build must use
  `set -e`, `--no-daemon`, reject unsigned output and rerun signer continuity.

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
- Physical offline/recovery, rotation and multi-window have not completed the
  required scoped device matrix.
- A real content delta is not published because the public v1.0.0 content hash
  and v1.1.1 candidate content hash are identical. Before a differing content
  pointer moves, the physical App must exercise delta reconstruction,
  full-bundle fallback, restart and previous rollback.
- The public update manifest still points to v1.0.0 with
  `appId=net.bdfz.weibian`, not the Direct package
  `net.bdfz.weibian.direct`. Therefore self-update is not verified and v1.1.1
  is neither published nor accepted.
- Canonical portal `i.rdfzer.com` returns 200. The 522
  `allinone.bdfz.net`/`portal.bdfz.net` aliases are noncanonical and are not
  used as release evidence. Companion is explicitly `not-applicable`; there is
  no Weibian WebView service.

## Recheck triggers

Repeat this review before changing identity, User Center payloads, update
manifest schema, content-delta schema, D1 ranking fields, Worker bindings,
network hosts, Android exported components, Room schema or release signing.

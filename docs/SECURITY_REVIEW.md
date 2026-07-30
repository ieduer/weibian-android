# Security review — v1.1.2 / code 4 source freeze

Date: 2026-07-29
Scope: Android Direct client, `weibian-content` Worker, D1 rankings, content and
update contracts, plus the task-owned immutable R2 content prefix. Shared User
Center, APIS, Pulse and portal implementations were treated as external
dependencies of this review. The separately controlled User Center registry
change is recorded below but is not re-reviewed here as App/Worker code.

## Outcome

No unresolved P1/P2 issue was found in the owned v1.1.2 / code 4 App source,
Worker or operator-documentation scope after independent freeze review. Direct
R2 immutable artifacts, `latest.apk` and `latest.json` are live pointer-last.
IN2020 passed the real App updater from code 3 to exact code 4 plus data/Session,
ranking, feedback, offline/recovery, rotation/multi-window, AI/annotation,
current-update, single-package and supplemental expanded-layout checks, then had
its settings restored. LE2120 installed the same code 4 and passed login,
ranking and feedback before the owner stopped further work; restoration of its
temporary Wi-Fi proxy is unconfirmed. GitHub v1.1.2 matches R2 and the new
landing passed as a zero-percent candidate, but this is still not full release
acceptance: the LE2120 matrix, a separate physical tablet and physical
active-corrupt → previous remain open. User Center registry and feedback v242
are live.

## Fixed in this candidate

| Severity | Finding | Resolution | Verification |
|---|---|---|---|
| High | Ranking identity hashing did not explicitly reject a missing or weak server pepper | A minimum 32-character server-only value is now required before any authenticated ranking sync | Worker unit test plus production unauthenticated ranking probe |
| Medium | Generic JSON responses could be read into memory without a byte limit | All App JSON responses are capped at 2 MiB before parsing | Android tests, lint and build |
| Medium | The outer progress-sync failure path could retry without the documented attempt bound | Both per-item and outer failures now stop after the same maximum attempt count | Source review, lint and build |
| Medium | Cleartext transport policy depended on platform defaults | `android:usesCleartextTraffic="false"` is explicit | Merged manifest and lint |
| Medium | Public rights wording contradicted the owner-authorized release scope | Landing page and repository documentation now point to the rights receipt | Production landing-page readback |
| Medium | A Worker-bundled “immutable” route could no longer serve an older content version after a new deployment | Authorized bundles now live in non-overwritten R2 hash paths; an exact code allowlist exposes reviewed versions | Public byte/size/hash parity and unknown-version 404 |
| Medium | Profile favorites and notes reused the same `chapterId` key in one `LazyColumn`, so the same chapter in both sections crashed the main thread | Keys are now section-namespaced (`favorite:*`, `note:*`, `achievement:*`) | Regression unit tests plus physical OnePlus pre-final code 3 candidate full-profile scrolling with no scoped App fatal |
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
- User Center is the ranking identity authority only. The App submits a bounded
  raw answer event; `weibian-content` authenticates the session through the
  service binding, loads the exact allowlisted immutable task, and derives
  correctness and points without trusting client score fields.
- Ranking D1 stores one first-recorded event per pseudonymous user/task,
  including bounded task/content identity, selected option, server-derived
  correctness/point, receipt time and Beijing receipt day. It stores no account
  slug, name, Cookie, password or free text. SQL values use prepared statements.
- `RANKING_PEPPER` is a database identity key, not a routine rotatable secret.
  Its backup belongs with D1 recovery authority. A forced change requires a
  versioned dual-key migration, verified row continuity and an explicit
  rollback; replacing it in place would split every learner's history.
- Ranking responses and authenticated endpoints are `no-store`; immutable
  content is hash-addressed.
- Gitleaks scanned the working tree with redaction enabled and found no secret.
- Worker tests, release guard tests, JavaScript syntax checks and the historical
  code3 Android gates passed at release checkpoint
  `e623e370a60bff33609e8bf5ad2748f559e20471`; GitHub Actions run
  `30466463323` is green. Current code4 clean source
  `e65dc572af19ed99cf520d52aa01de72508680a9` passed independent source review,
  dual-channel unit/lint, instrumentation, signed R8 build and GitHub Actions run
  `30516534134`.
- The physical OnePlus pre-final code3 candidate profile regression,
  AI讲解／non-sensitive 高考批改 and session persistence paths passed without
  exposing credentials or student content. This evidence does not transfer to
  the final exact APK.
- The only release signing authority is
  `/Users/ylsuen/.android/weibian-release.env`, and its signer matches the
  historical v1.0.0 signing certificate. The current Direct APK is 2,819,959
  bytes, SHA-256
  `956810c903005680ba2e77a2c71964956cd2beac428e840862fc0a33724e15c3`,
  with signer certificate SHA-256
  `a40f3956296d09ca2c6d8c3ec23f4f1d5470cb8ca6a5d4a69a9f19eb39941282`.
  The Play APK is 2,819,963 bytes / SHA-256
  `7bf92fcfc4fab561aee5f2e95a4ad80d67b9c7161778a667b8f7b33cc9427f7f`;
  the AAB is 4,988,101 bytes / SHA-256
  `6a37903152ede8c5a9b4f9d547af99454cb75d501f19e3b96491969131b132a4`.
  Direct immutable APK and `release.json` public readbacks match.
- User Center v242 `ec273922-1ec4-442b-8c84-9a5e2f7fcdf5` is at 100%
  production traffic with representative dependency probes and zero observed
  errors; the exact feedback rollback is v240
  `96b9db71-a595-4ae3-a557-288b49bffd2f`.

## Accepted or external residual risk

- The temporary native credential adapter is accepted in
  `IDENTITY_ADR.md`. It must be replaced when User Center exposes a registered
  standalone-App browser handoff; Companion credentials or client identity must
  not be reused.
- TLS certificate pinning is intentionally not used. The fixed HTTPS origins
  rely on Android's system trust store so normal certificate rotation does not
  brick login or updates.
- User Center registration and feedback v242 are live; v240 is the exact
  Worker rollback. The backend authenticated/idempotent canary and IN2020 final
  code4 feedback path passed; LE2120 also produced an authenticated saved and
  notified receipt before the owner stopped further work.
- IN2020 passed a reversible forced expanded-layout run and every captured
  setting was restored. This is supplemental evidence only: the current fleet
  rule still requires a separate physical tablet for adaptive-layout and
  in-place-upgrade acceptance.
- Final code4 offline/recovery, rotation and multi-window passed on IN2020 but
  still require an independently authorized LE2120 pass. The temporary LE2120
  per-network proxy may still be `127.0.0.1:9`; only an owner-confirmed manual
  restore to None closes that device-state risk.
- A differing B bundle (`4a97b261…e3703`) and A→B delta
  (`83d407be…8b1f`) are publicly readable immutable staging objects; their
  canary version was exercised on both registered phones and then removed from
  traffic. Both phones passed delta reconstruction, deliberate delta rejection
  → full-bundle fallback, restart readback and stable-A restore; ordinary users
  receive stable A.
- The public update manifest now points to v1.1.2/code4 at the exact immutable
  `…/v1.1.2/956810c9/` path; `latest.apk` and `latest.json` were written after
  immutable readback, with the pointer last. Both registered phones accepted
  that updater path. GitHub v1.1.2 is byte-identical; production landing remains
  historical while candidate `1ce95b1a…` stays at zero percent.
- The final exact `de47da19…8da67` code3 APK is historical prior-version
  evidence. Both phones now contain exact code4; LE2120 has only the bounded
  updater/login/ranking/feedback subset and cannot be counted as a full pass.
- Full-bundle fallback is not physical active-corrupt → previous proof. That
  deliberate corruption test remains open until its risk is explicitly approved.
  The production APK is non-debuggable and exposes no provider or test hook for
  mutating private `filesDir/content/{active,previous}`. Any same-package
  production-signed instrumentation/helper therefore needs separate explicit
  authorization before use.
- Canonical portal `i.rdfzer.com` returns 200. The 522
  `allinone.bdfz.net`/`portal.bdfz.net` aliases are noncanonical and are not
  used as release evidence. Companion is explicitly `not-applicable`; there is
  no Weibian WebView service.

## Recheck triggers

Repeat this review before changing identity, User Center payloads, update
manifest schema, content-delta schema, D1 ranking fields, Worker bindings,
network hosts, Android exported components, Room schema or release signing.

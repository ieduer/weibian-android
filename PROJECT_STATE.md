## 2026-10-05 — Candidate and published-content test compatibility

The native source review tests now run against both the exact reviewed candidate and the currently published bundle. A test-only review projection exercises the new parsing and feedback rules when CI bootstraps the supported old bundle; it is never an application content source. Packaging assertions reject a generated candidate with missing reviews, require the exact reviewed digest when present, and accept the old bundle only under its published lock. Each content scenario passes161 tests in each distribution channel; the actual candidate assets were restored afterward. Runtime source, candidate content, lint and APK hashes are unchanged from086327a.

The inherited event-v2 source-only workflow has a separate existing conflict: its frozen allowlist already rejects14 paths on current GitHub main921d368. Its code/contract and inactive status were not changed or bypassed. This is an outstanding CI/release governance disposition, not a passed check. Evidence: `serial7/weibian-ci-content-compatibility.json` and `serial7/weibian-inherited-ci-boundary.json` in the Analects consolidation report.

## 2026-10-05 — Source-reviewed exam candidate, not published

Transaction `20261005-weibian-exam-source-review` extends the fixed-source candidate with the exact reviewed GK source `e0dcee540ad340901ec48112292e8c6cd19d5e21`. All seven groups and23 legacy question IDs receive additive review metadata. Every prior exam field, prompt and score remains intact. The native reader distinguishes current source corrections from historical references; future feedback uses the corrected material/answer and refuses numeric grades when printed subpart scoring is unknown. No stored attempt, Room schema, identity or central score changes.

Candidate content0b3170748035504b, SHA 0b3170748035504b44f947017855ec14893884d6c18acd996b6b76cfe454a2c9, 899729 bytes. Nine source/history tests and content validation pass. Both native variants pass160 tests each, lint (zero errors; nine existing warnings each) and debug assembly. Both APKs contain exact candidate content/manifest bytes. No device or production acceptance is claimed. The public manifest was read back asfc68413c7b70da0e. Full scope, consumer gates and rollback: `docs/CONTENT_SOURCE_PIPELINE.md`; shared receipt: `/Users/ylsuen/CF/reports/operations/shared_hub_changes/2026-10-05-weibian-exam-source-review.json`.

## 2026-10-05 — Fixed-source content candidate, not published

The content builder now reads 67 exact accepted upstream Git blobs, restores all 23 historical exam IDs from legacy numbered fields, validates a published-history contract, and correctly maps 17 concepts / 15 figures. Candidate2b6ffd83aff1a564 changes only concept/figure collections; the original corpus, bank, aliases and all exam bytes remain exact. Seven source/history tests and all six native gates pass against the actual candidate assets. See `docs/CONTENT_SOURCE_PIPELINE.md` for evidence, generation, known exam uncertainties, pending Web/App release acceptance and rollback. Public content remainsfc68413c7b70da0e; no device, production or student data changed.

# Project state

Last updated: 2026-08-30 PDT

## 2026-08-30 v1.1.3 owner-waived production release

The public Direct release is now v1.1.3 / versionCode 5. The owner explicitly
waived the remaining physical-device and App acceptance gates and instructed a
direct release; those gates are **waived and unverified**, not passed. No phone,
installed App, owner data or device setting was touched, and LE2120 remained out
of scope.

The accepted Direct APK is 2,819,955 bytes with SHA-256
`9a1d67ef5ce0f43c9a8ed423c72c30cc8742f21123ebdca5399c5dd671ea2933`.
It is package `net.bdfz.weibian.direct`, v1/v2 signed by the existing certificate
SHA-256 `a40f3956296d09ca2c6d8c3ec23f4f1d5470cb8ca6a5d4a69a9f19eb39941282`,
and embeds Git revision `abb140e23fa3eae5b532d03f86389e8d4992e2fd`.
Git tag and GitHub Release `v1.1.3` target that revision. R2 and GitHub serve
byte-identical APK and 528-byte `release.json` objects; the metadata SHA-256 is
`530ca9603627e529d9e70187cf1a14794012f759fa0e7679fbf847f62719f348`.

R2 immutable authority is
`https://img.bdfz.net/apps/weibian-android/releases/v1.1.3/9a1d67ef/weibian-1.1.3.apk`.
After exact-URL edge-cache purges, the bare `latest.apk` was read back with the
same bytes and `latest.json` was moved last and read back pointing to that
immutable URL. The landing-only source change was merged in PR #6; functional
main `88a7abbb7d47bd16951e0c73d011c6a391270fe2` is deployed as Worker version
`8e4a53a2-a79f-4989-9f6e-287724553386` in deployment
`52dc0a92-a906-4c67-a909-63da1992bed7` at 100%. Immediate Worker rollback is
`0b5f49e2-8ee3-4be3-98da-2d93ab0244ae`; mutable App rollback is the retained
v1.1.2 APK and metadata, restored APK-first with exact purge/readback and JSON
last. Immutable v1.1.3 objects and the GitHub Release are not deleted on
rollback.

Live release readback returned the exact immutable v1.1.3 landing href,
`/api/health` with 512 chapters and 1,045 annotations, and verified APIS caller
identity `weibian`. Installed v1.1.2 clients remain supported by the bounded
legacy AI lane only until `2026-11-26T13:04:40Z`; the lane was not extended.

## 2026-08-30 v1.1.3 maintenance-release attempt (superseded history)

The public Direct release remains v1.1.2 / versionCode 4. A fresh clean build
from exact `main` `4a9c6ed97ab4a40db3629a6f913515ad61c72b3f` produced a signed
v1.1.3 / versionCode 5 Direct candidate and passed both-channel unit tests,
lint, release assembly, the release metadata guard, package/signer continuity,
and the current read-only Worker, User Center, Pulse, R2 and GitHub baselines.
The Direct candidate was 2,819,956 bytes with SHA-256
`80a8f38570883f2ca0a64d96684ca7c08491cd2680849f2f30fed3f2c1aa7c17`.
This candidate was never uploaded or published and is not a retained release
authority.

The mandatory selected-phone gate could not start: ADB enumerated no attached
or registered device, and the historical IN2020 wireless endpoint was no
longer routable. No App was installed, no device or device setting was changed,
and LE2120 was not contacted. Therefore no v1.1.2-to-v1.1.3 in-place upgrade,
canonical identity lifecycle, feedback/update/data/outbox persistence,
expanded-layout, scoped-log or baseline-restoration claim is made.

Publication failed closed before any immutable v1.1.3 object, GitHub Release,
mutable APK alias or `latest.json` pointer was changed. The landing and public
update surfaces continue to resolve to accepted v1.1.2. The next release task
must reconnect and identify IN2020 by hardware serial `6393cccf`, rebuild and
re-run every non-device and physical gate, and move `latest.json` last. The
bounded legacy AI lane still expires at `2026-11-26T13:04:40Z`; it must not be
extended by default.

## 2026-08-28 APIS caller-auth migration

The App source on `main` is now v1.1.3 / versionCode 5. Its AI path targets
the same-origin `weibian.bdfz.net` Worker rather than calling
`apis.bdfz.net` from the device. The Worker owns caller `weibian`, keeps the
caller credential server-side, and reaches APIS through its Service Binding.

Accepted App source commits are `55e6bd3` and `c94a977`; the B5-3 caller-check
source is merged on main `98db37e` through PR #3, after the two operations
documents were separately accepted through PR #2. Signed APK and AAB artifacts
were built for the App migration. The content Worker deployment
`301a8dcb-1f9a-4c67-b9ea-61d049f68441` runs version
`0b5f49e2-8ee3-4be3-98da-2d93ab0244ae` at 100 percent; immutable rollback is
`9060729e-fdae-402c-843f-c04971e274a3`. A real same-origin product request reached APIS as verified caller
`weibian` and returned 200 with request ID
`0c2c789c-7f33-4e73-a88a-d74ebeb7ecbb`.

The no-provider `/__caller-check` route uses the same APIS Service Binding,
caller ID and server-only credential as the existing Worker AI path. Exact
Node 24.18.0 Worker tests passed 30/30; both Android distribution channels
passed unit tests, lint and assembly; strict Worker dry-run, exact-commit
gitleaks, the clean-source deploy gate and hosted GitHub Verify all passed.
Candidate `0b5f49e2-8ee3-4be3-98da-2d93ab0244ae` returned verified JSON at 0%,
1%, 5% and 100%, and the final fleet table reached 25/27.

Installed v1.1.2 clients are protected during caller-auth enforcement by an
exact-origin legacy lane for `https://weibian.bdfz.net`. The lane is already
active, allows at most 50 provider attempts per Pacific quota day and one
in-flight request, and has a hard expiry at `2026-11-26T13:04:40Z`. It is
independent of the main verified-caller pool and is not a permanent exception.

Physical installation acceptance and changing the public `latest.apk` remain
an App release follow-up owned by suen. They do not block APIS caller-auth
enforcement because both the new proxy path and bounded old-client lane are
already live. The follow-up must complete before the hard expiry; if it does
not, suen must explicitly review the release state before the lane expires.
After expiry, unupgraded clients lose AI access. Do not extend the lane by
default.

This migration does not activate or alter the event-v2 source candidate below.
Its older source-scope validator is not release authority for this accepted
Worker-only route change.

## Event-v2 candidate baseline (2026-08-15 historical context)

When this inactive candidate was authored, the production-supported Direct
release was v1.1.2 / versionCode 4. Its then-current source, Worker, APK,
signing, device, rollback, and live verification facts remain in
`docs/MAINTENANCE_MANUAL.md`. The 2026-08-28 migration section above supersedes
that runtime baseline without activating this candidate.

This branch is based on exact `main`
`f17e5d54e10f34047fac70424e63e836dcf002ea`. It adds only an inactive,
unbound event-v2 source candidate. It does not change the Android App, Room
schema/outbox, Worker runtime/import graph, routes, Wrangler bindings, D1
migrations, Cloudflare resources, User Center, delivery, scoring, or any live
deployment.

## Candidate objective

The candidate can project a row already persisted by the existing
`weibian_answer_events_v2` first-answer ledger into a
`bdfz-learning-evidence-event-v2` envelope. The envelope is always
`pending_mapping`, `trace`, `none`, `non_scoring`, with all score values null.
It retains the existing source authority:

- Android Room keeps the first authenticated authored answer in its outbox;
- the Worker revalidates exact `contentVersion`, task/chapter/option, semantic
  digest, and answer key;
- D1 freezes one first answer per pseudonymous owner and canonical task;
- the existing `event_id` conflict and replay behavior remains authoritative;
- projection time comes only from the persisted server receipt time.

The candidate accepts no client verdict, score, time, user id, pseudonym,
owner key, or payload row. Its projection input is exactly the bounded session
header plus the existing server receipt. A future runtime composition would
have to inject an owner-scoped D1 row reader, a named User Center
`identityRpc.resolveSession` method, and a trusted Weibian source-auth
`sourceIdentity.resolveOwner` method. Both identity dependencies must resolve
the exact same bounded cookie before any ledger lookup.

## Hard blockers

1. At audited User Center main
   `f8d086cb9a511bc5ff310ef867b276d889f6c1e3`, the public/default
   `/api/session` response omits the numeric database user id. It is not
   accepted by the candidate.
2. User Center has named GrowthEvidence RPC entrypoints for registered sources,
   but no reviewed `WeibianGrowthEvidence` entrypoint. The existing Weibian
   binding calls the default Worker and resolves only slug plus an HMAC
   pseudonym. Therefore no positive immutable numeric UC `userId` can currently
   be produced for this candidate.
3. No trusted `sourceIdentity.resolveOwner` dependency is connected to the
   candidate. A caller-supplied `ownerUserKey` is rejected and cannot substitute
   for same-cookie source authentication.
4. No Weibian event-v2 source contract, mapping, importer, route, Queue/RPC
   delivery method, binding, or central policy has been reviewed or configured.
5. Runtime import, route connection, binding configuration, migration apply,
   delivery, scoring, activation, and production deployment all remain false
   and unauthorized.

## Next separately governed step

Do not activate this branch. A later cross-repository change would require a
reviewed source-specific UC named RPC entrypoint, a trusted same-cookie Weibian
source-owner resolver, an exact source contract and pending-mapping consumer
path, a new synchronized-change receipt, protected-surface review, rollback,
and explicit production authorization. No UC or Cloudflare change is part of
this pull request.

Candidate operations and rollback are documented in
`docs/EVENT_V2_SOURCE_CANDIDATE_OPERATIONS.md`.

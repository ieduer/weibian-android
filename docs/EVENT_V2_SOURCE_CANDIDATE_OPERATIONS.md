# Event-v2 source candidate operations

Status: `blocked_inactive_source_only`

This document covers only the source candidate introduced from exact Weibian
main `f17e5d54e10f34047fac70424e63e836dcf002ea`. The production authority remains
`docs/MAINTENANCE_MANUAL.md`. Nothing here is a deploy, activation, migration,
delivery, scoring, or User Center change procedure.

## Scope and source of truth

- Machine contract:
  `contracts/weibian-first-answer-event-v2-candidate.json`
- Pure adapter/projection:
  `candidate/weibian-event-v2/adapter.mjs`
- Hostile tests:
  `candidate/weibian-event-v2/adapter.test.mjs`
- Protected-surface verifier:
  `candidate/weibian-event-v2/verify-source-scope.mjs`
- PR-only dual-Node workflow:
  `.github/workflows/weibian-event-v2-candidate-pr.yml`

The existing authoritative path remains unchanged: Room
`verified_answer_outbox` -> Worker exact content/answer recomputation -> D1
`weibian_answer_events_v2`. The public projection method accepts exactly the
bounded session header and the existing server receipt; it never accepts a
`user_key` or other owner selector. The same bounded session header must first
resolve through both injected dependencies: `identityRpc.resolveSession()` for
the positive immutable numeric UC user id and `sourceIdentity.resolveOwner()`
for the authenticated Weibian `ownerUserKey`. Both authorities complete before
the owner-scoped ledger lookup. The adapter then rechecks row ownership, receipt
identity, result consistency, semantic digest, and server time before projecting.

The event intentionally carries no answer, correctness, points, score, slug,
pseudonym, cookie, or free text. It is a non-scoring trace with null score fields
and `pending_mapping`; the existing Worker/D1 remains the event-id collision and
first-answer authority.

## Identity failure boundary

The public/default User Center `/api/session` response is not accepted. At
audited UC main `f8d086cb9a511bc5ff310ef867b276d889f6c1e3`, that response uses
`formatUser()` and omits the numeric database id. Existing source-specific
GrowthEvidence methods are named `WorkerEntrypoint` RPC topology, and no
`WeibianGrowthEvidence` class exists there.

The adapter therefore requires exact dependency injection of
`identityRpc.resolveSession(cookieHeader)` and
`sourceIdentity.resolveOwner(cookieHeader)`. The UC response must contain only
authenticated `sourceSiteKey=weibian` plus a positive safe-integer numeric
`userId`. The source response must contain only authenticated
`sourceSiteKey=weibian` plus the validated 64-character lowercase hexadecimal
`ownerUserKey`. Both receive the exact same bounded cookie. Public response
shapes, `payload.user.id`, strings, zero/negative ids, slug, request-supplied
owner keys, and cross-site responses all fail closed. Transport errors expose
only stable codes, never the cookie or an upstream error cause.

No candidate dependency is currently connected. The existing source Worker
still owns its pseudonymous ranking identity, but this source-only PR neither
imports that implementation nor claims that the public User Center helper can
produce the numeric id. Runtime composition remains a separately reviewed
blocked step.

## Verification

Use either exact supported Node release. No package download is needed:

```bash
npm test
node --test scripts/inactive-candidate.test.mjs
npm run verify:inactive
```

The PR-only workflow runs the existing hostile adapter tests, negative regression
tests and the inactive gate on exact Node 22.21.1 and 24.18.0. The ten-minute
ceiling, read-only permissions, SHA-pinned actions and cancellation remain.

`verify:candidate` and its original code/contract remain unchanged. They are the
historical source-only transaction verifier, valid at candidate revision
`98590b1deb3e5bc0fffd4590cb1d2c41c32828c4` against source main
`f17e5d54e10f34047fac70424e63e836dcf002ea`. Running that transaction's whole-repository
allowlist on later product PRs rejects even accepted main: main
`921d36811c82c97254d07a050c32b9fc1850433a` already has fourteen paths outside it.
Do not enlarge that historical allowlist or change its source main.

The ongoing `scripts/verify-inactive-candidate.mjs` instead binds three exact,
ancestor-checked Git revisions and fails closed if any is unavailable:

- Candidate revision `98590b1...`: recheck the exact nine-path historical diff
  and all thirteen original protected digests, then require the current
  adapter, hostile tests, contract and original verifier to remain byte-exact.
  No additional file may enter the frozen candidate directory.
- Accepted runtime main `921d368...`: require the protected Worker, identity,
  Room/outbox and migration files to remain byte-exact. The complete migration
  and Room schema inventory is frozen too.
- CI correction `f8fd08b...`: freeze only `.github/workflows/verify.yml` to the
  SDK setup correction whose exact-head Android run37397045699 passed. This
  source anchor is not a production or device acceptance.

The gate also scans tracked and non-ignored new Worker and Android source
(including flavor paths) for candidate imports, factories and named RPC
markers. It rejects missing files and symlinks, activation or numeric score
fields, new dependencies, and loss of the PR-only dual-Node checks. There is no
skip-on-unrelated-change condition. Protected pins require a new reviewed
source disposition for any future change; moving `main` never moves a pin.
Ordinary content/UI changes outside these surfaces continue under the separate
Android, content, release and device gates.

This changes repository verification only. It does not activate the adapter,
change a shared contract, migrate data, deploy, or make this candidate eligible
for scoring. Negative tests cover protected drift, new wiring/migrations,
symlinks, historical tampering, score changes and workflow/dependency drift.

## Explicitly forbidden actions

- importing the adapter from `worker/src/index.js` or any App source;
- adding a route, Queue, RPC call, service-binding entrypoint, or Wrangler
  binding;
- using public `/api/session` or deriving the numeric user id from slug,
  pseudonym, request body, or client storage;
- accepting `ownerUserKey` from the projection caller or selecting a ledger
  owner before both identity dependencies resolve the same bounded cookie;
- adding or applying a D1/Room migration;
- sending an event to User Center or any other destination;
- activating mapping, eligibility, scoring, A-F/A+ effects, or delivery;
- deploying, dry-running Wrangler, changing Cloudflare state, or merging this
  draft without a separately authorized synchronized transaction.

## Rollback and retention

Before merge, close the draft PR and delete its branch. After a source-only
merge, revert the candidate commit. There is no data, Worker version, binding,
migration, Queue, App, UC, or deployment rollback because none is changed.

The isolated local clone may be deleted after its commit is pushed, the draft
PR and checks are readable remotely, the tree is clean, and no process or open
handle owns it. Until then retain it with this reason; never delete a canonical
checkout or any other transaction scratch.

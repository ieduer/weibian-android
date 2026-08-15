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
`weibian_answer_events_v2`. The adapter may receive only a row loaded by exact
canonical `event_id` and the already authenticated legacy `user_key`. It first
resolves the same request through an injected named UC RPC and then rechecks row
ownership, receipt identity, result consistency, semantic digest, and server
time before projecting.

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

The adapter therefore requires dependency injection of a named RPC object with
`resolveSession(cookieHeader)`. Its response must be authenticated, bound to
`sourceSiteKey=weibian`, and contain a positive safe-integer numeric `userId`.
Public response shapes, `payload.user.id`, strings, zero/negative ids, slug, and
the existing HMAC `user_key` all fail closed. Errors never include the cookie.

## Verification

Use either exact supported Node release. No package download is needed:

```bash
npm test
npm run verify:candidate
```

The PR-only workflow repeats both commands and syntax checks on exact Node
22.21.1 and 24.18.0. The verifier requires the exact source main to be an
ancestor, allows only the nine declared candidate files, hashes existing
Worker/Room/CI protected surfaces, rejects runtime candidate imports or binding
markers, and checks every activation flag remains false.

The full Android/Worker workflow may still run because the repository's
existing `verify.yml` applies to all pull requests. That existing workflow is
not modified by this candidate.

## Explicitly forbidden actions

- importing the adapter from `worker/src/index.js` or any App source;
- adding a route, Queue, RPC call, service-binding entrypoint, or Wrangler
  binding;
- using public `/api/session` or deriving the numeric user id from slug,
  pseudonym, request body, or client storage;
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

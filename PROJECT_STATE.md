# Project state

Last updated: 2026-08-15

## Production authority

The production-supported Direct release remains v1.1.2 / versionCode 4. Its
accepted source, Worker, APK, signing, device, rollback, and live verification
facts remain in `docs/MAINTENANCE_MANUAL.md` and are unchanged by this branch.

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

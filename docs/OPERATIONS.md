# lunyu-yizhu-android operations

Last normalized: 2026-08-30 PDT
Owner: suen
Lifecycle: production-supported App and content Worker; inactive event-v2 source candidate remains separate
Data class: student_owned; see `docs/MAINTENANCE_MANUAL.md` for the reviewed boundaries
Documentation status: v1.1.3 public artifacts, update pointers, landing, Worker deployment and rollback were read back live; physical-device acceptance was explicitly waived by the owner and remains unverified.

## Quick start

- Canonical local path: `/Users/ylsuen/CF/apps/lunyu-yizhu-android`
- Git authority: `ieduer/weibian-android`
- Functional release branch/HEAD: `main` / `88a7abbb7d47bd16951e0c73d011c6a391270fe2`; APK-embedded release revision and tag target: `abb140e23fa3eae5b532d03f86389e8d4992e2fd`
- Runtime config: `worker/wrangler.toml`; Worker `weibian-content`; custom domain `weibian.bdfz.net`
- Current state: [PROJECT_STATE.md](../PROJECT_STATE.md)
- Workspace resource routing: [project resource index](../../reports/operations/project_resource_index.md)
- Documentation standard: [project operations standard](../../runbooks/project_operations_documentation_standard.md)
- Production mutation still requires fresh target, binding, verification and rollback readback; the values below record the v1.1.3 release, not standing authorization for a later deploy.

## Current Direct release

- Public authority is v1.1.3 / versionCode 5. The Direct APK is 2,819,955
  bytes, SHA-256 `9a1d67ef5ce0f43c9a8ed423c72c30cc8742f21123ebdca5399c5dd671ea2933`,
  and uses the existing signer certificate SHA-256 `a40f3956…41282`.
- Immutable APK:
  `https://img.bdfz.net/apps/weibian-android/releases/v1.1.3/9a1d67ef/weibian-1.1.3.apk`.
  GitHub Release `v1.1.3`, R2 immutable bytes, bare `latest.apk`, and
  pointer-last `latest.json` were read back byte-identical.
- The owner explicitly waived physical-device and App acceptance. This is a
  release authorization and scope decision, not evidence that those gates
  passed. No phone, package, owner data or setting was changed; LE2120 remained
  out of scope.
- `CAPABILITY_FIT=no-new-capability`: this release reused the existing Worker,
  Static Assets, D1, R2 and Service Bindings. No runtime, binding, lifecycle,
  compatibility date or shared-hub contract was added or changed.

## Existing project documentation relationship

This `docs/OPERATIONS.md` is the single project-local operations entrypoint.
Existing detailed manuals remain authoritative annexes for their exact scope;
historical handovers and ledgers are evidence, not current state.

- [docs/MAINTENANCE_MANUAL.md](MAINTENANCE_MANUAL.md)

## Project and runtime inventory

| Project ID | Runtime type | Resource | Domains |
| --- | --- | --- | --- |
| `weibian` | Android App + Worker | `weibian-content` | `weibian.bdfz.net` |

Live Cloudflare matching is metadata-only and does not prove application health:

| Resource | Live type | Readback | Detail |
| --- | --- | --- | --- |
| `weibian-content` | Worker + Static Assets | live | version `8e4a53a2-a79f-4989-9f6e-287724553386` at 100%; deployment `52dc0a92-a906-4c67-a909-63da1992bed7` |

## Authority and dependencies

- Project names: `lunyu-yizhu-android`, Worker `weibian-content`, APIS caller `weibian`
- Catalog owner: suen
- Data classes: `student_owned`; no raw student content belongs in operational receipts
- Identity modes: Worker validates User Center sessions for ranking paths; the AI proxy keeps the APIS caller credential server-side
- User Center required: yes for authenticated ranking/data paths; `/__caller-check` deliberately does not enter User Center, D1, R2 or provider paths
- Pulse measurement: `worker_analytics` for `weibian.bdfz.net`, per the maintenance manual
- Runtime bindings: Static Assets `ASSETS`, D1 `DB`, R2 `CONTENT_R2`, Service Bindings `USER_CENTER` and `APIS`; secret names are inspected separately and values are never documented
- Shared User Center, APIS, nav, image, Pulse, App, clone-family, and VPS effects must be checked through workspace topic runbooks; this file does not weaken those gates.

## Resource location and restore

- Source authority: `/Users/ylsuen/CF/apps/lunyu-yizhu-android`; Git/GitHub authority above.
- App release, content bundle, R2 and restore authorities remain in `docs/MAINTENANCE_MANUAL.md`. This release created immutable public artifacts but no local archive or backup; build outputs and task staging are reproducible derivatives and are removed at closeout.

Catalog backup evidence:
- Immutable Worker versions and immutable R2 content objects are separate rollback authorities; D1/data rollback is not implied by Worker rollback.

Catalog restore evidence:
- Use the exact procedures in `docs/MAINTENANCE_MANUAL.md`; do not reconstruct content, clear D1, or overwrite immutable R2 objects as a code rollback.

Before deleting any local resource, satisfy the workspace path-preserving archive, remote readback, isolated restore, receipt, handbook, and project-state gates.

## Preflight and AI ownership

1. Read `/Users/ylsuen/CF/AGENTS.md`, this file, `PROJECT_STATE.md`, and linked annexes.
2. Inspect `git -C "/Users/ylsuen/CF/apps/lunyu-yizhu-android" status --short` when Git-backed.
3. Inspect recent `reports/agent_action_log.jsonl` ownership.
4. Resolve the exact source, Worker/Pages/VPS/App target, domains, bindings, data, and rollback live.
5. Append a scoped `start` row before the first mutation.
6. Preserve unrelated dirty work; never reset, clean, broad-checkout, or stash another task's changes.

## Build, test, and local verification entrypoints

Current B5-3 verification entrypoints:

- Worker: exact Node 24.18.0, `node --test worker/test/*.test.mjs`, syntax check, content build check, strict Wrangler dry-run and exact-commit gitleaks.
- App: `./gradlew :app:testDirectDebugUnitTest :app:testPlayDebugUnitTest :app:lintDirectDebug :app:lintPlayDebug :app:assembleDirectDebug :app:assemblePlayDebug`.
- Source gate: `/Users/ylsuen/CF/scripts/git-deploy-gate.sh`; B5-3 did not use `BDFZ_DEPLOY_GATE_OVERRIDE`.

Run only commands supported by the current project toolchain and verify expected outputs in the project before using them as release evidence.

## Health and business-path verification

Catalog health probes:
- `curl -sS https://weibian.bdfz.net/api/health | jq`
- `curl -sS -H 'Cache-Control: no-cache' -H 'Pragma: no-cache' "https://weibian.bdfz.net/__caller-check?verify=<UNIQUE>" | jq`

Catalog contract checks:
- `/__caller-check` must return HTTP 200 JSON with `ok=true`, caller `weibian`, `identityStatus=verified` and a nonempty request ID.
- Run `/Users/ylsuen/CF/_meta/scripts/verify-apis-caller-identities.mjs` after each APIS/Worker percentage step; a deterministic route/configuration failure stops immediately, while an existing green 503/timeout follows D20 two-of-three confirmation.

Also verify authentication boundaries, data read/write behavior, browser/device path, monitoring, clone-family and shared-hub regressions as applicable. HTTP 200 or a build alone is insufficient.

## Preview, deployment, and rollback

Catalog deploy commands (not authorization; fresh preflight remains mandatory):
- From `worker/`, upload an immutable version and use Wrangler version deployments for 0% / 1% / 5% / 100% with exact readback at every step.

Rollback/failback authorities:
- Immediate Worker predecessor: `0b5f49e2-8ee3-4be3-98da-2d93ab0244ae`; restore it at 100% without clearing D1, altering APIS/User Center, or extending the legacy lane.
- Current production: deployment `52dc0a92-a906-4c67-a909-63da1992bed7`, version `8e4a53a2-a79f-4989-9f6e-287724553386` at 100%.
- App pointer rollback is independent: restore the retained exact v1.1.2 APK to
  `latest.apk`, purge only that URL and prove its public hash, then restore the
  retained v1.1.2 `latest.json`, purge only that URL and prove it. Do not delete
  immutable v1.1.3 objects or the GitHub Release.

For data-backed projects, immutable code rollback does not restore D1/KV/R2/DO/Queue state. Use backup/restore or backward-compatible forward-fix procedures verified for the exact resource.

## Monitoring, privacy, cost, and incidents

- Monitoring coverage: Worker Observability plus Pulse `worker_analytics`
- Measurement: health/readback and APIS caller identity; user-level payloads are excluded from receipts
- Never record secret values, cookies, sessions, private keys, raw student content, or sensitive payloads.
- Verify current logs, errors, cost/usage, limits, owner, stop condition, and incident runbook before representing runtime health.

## Verification standard

1. Source of truth: local/Git/GitHub authority above, refreshed before mutation.
2. Health probe: catalog probes above plus expected response semantics.
3. Contract/business path: catalog checks plus auth/data/UI/device behavior.
4. Deploy and forbidden actions: catalog command above; no deploy from dirty, duplicate, reconstruction, archive, or unverified source.
5. Dependency regression: matrix fan-out, shared hubs, clone family, App/VPS as applicable.
6. Backup/restore: catalog evidence above; missing exact evidence is blocking for writes/deletion.
7. Rollback/failback: catalog authority above, refreshed live before release.
8. Last verified: 2026-08-30 PDT. The signed v1.1.3 artifact passed package,
   signer, metadata and byte-integrity gates; GitHub/R2/alias/pointer readbacks
   matched. Landing PR #6 was deployed through 0% / 1% / 5% / 100%; production
   health and verified caller identity passed. Physical-device acceptance was
   owner-waived and is not claimed. The bounded old-client lane still expires
   at `2026-11-26T13:04:40Z`.

## Synchronized documentation and handoff

Any change to source authority, architecture, dependencies, runtime resources,
deployment, data, backup/restore, verification, monitoring, incidents, rollback,
or ownership must update this manual in the same task. Accepted version,
objective, blockers, deployment state, rollback anchor, and next action must
update `PROJECT_STATE.md` in the same task.

Every AI closeout must record changed files, generated artifacts, tests, live
version/deployment, rollback, dirty-tree state, unresolved follow-ups, and the
manual/state updates in `reports/agent_action_log.jsonl`. Chat is not a durable handoff.

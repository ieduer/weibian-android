import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import {
  createWeibianFirstAnswerEventV2Candidate,
  WEIBIAN_EVENT_V2_CANDIDATE_CONSTANTS,
  WeibianEventV2CandidateError,
} from './adapter.mjs';

const OWNER_A = 'a'.repeat(64);
const OWNER_B = 'b'.repeat(64);
const EVENT_ID = 'weibian_answer_00000001';
const RECEIVED_AT_MS = Date.parse('2026-08-15T10:00:00.000Z');
const NOW_MS = Date.parse('2026-08-15T10:01:00.000Z');
const COOKIE_A = 'bdfz_uc_session=test-only-session-a';
const COOKIE_B = 'bdfz_uc_session=test-only-session-b';

function row(overrides = {}) {
  return {
    event_id: EVENT_ID,
    user_key: OWNER_A,
    canonical_task_id: 'task.lunyu.1',
    chapter_id: 1,
    content_version: 'fc68413c7b70da0e',
    task_semantic_digest: 'c'.repeat(64),
    selected_option: 'option-a',
    correct: 1,
    points: 1,
    received_at_ms: RECEIVED_AT_MS,
    beijing_day: '2026-08-15',
    ...overrides,
  };
}

function receipt(overrides = {}) {
  return {
    eventId: EVENT_ID,
    canonicalEventId: EVENT_ID,
    taskId: 'task.lunyu.1',
    status: 'accepted',
    recorded: true,
    correct: true,
    points: 1,
    receivedAt: '2026-08-15T10:00:00.000Z',
    beijingDay: '2026-08-15',
    ...overrides,
  };
}

function identity(userId = 343) {
  return { authenticated: true, sourceSiteKey: 'weibian', userId };
}

function sourceOwner(ownerUserKey = OWNER_A) {
  return { authenticated: true, sourceSiteKey: 'weibian', ownerUserKey };
}

function adapter({
  identityResult = identity(),
  sourceIdentityResult = sourceOwner(),
  ledgerRow = row(),
  order = null,
  identityError = null,
  sourceIdentityError = null,
  ledgerError = null,
  nowMs = NOW_MS,
} = {}) {
  return createWeibianFirstAnswerEventV2Candidate({
    identityRpc: {
      async resolveSession(cookieHeader) {
        order?.push(['identity', cookieHeader]);
        if (identityError) throw identityError;
        return identityResult;
      },
    },
    sourceIdentity: {
      async resolveOwner(cookieHeader) {
        order?.push(['sourceIdentity', cookieHeader]);
        if (sourceIdentityError) throw sourceIdentityError;
        return sourceIdentityResult;
      },
    },
    verifiedAnswerLedger: {
      async readByEventAndOwner(query) {
        order?.push(['ledger', query]);
        if (ledgerError) throw ledgerError;
        return ledgerRow;
      },
    },
    clock: () => nowMs,
  });
}

async function expectCode(promise, code) {
  await assert.rejects(
    promise,
    (error) => error instanceof WeibianEventV2CandidateError && error.code === code,
  );
}

test('machine contract keeps every activation and delivery switch false', async () => {
  const contract = JSON.parse(await readFile(
    new URL('../../contracts/weibian-first-answer-event-v2-candidate.json', import.meta.url),
    'utf8',
  ));
  assert.deepEqual(contract.activation, {
    runtimeImported: false,
    routeConnected: false,
    bindingConfigured: false,
    migrationApplied: false,
    deliveryEnabled: false,
    scoringActive: false,
    productionDeploymentAuthorized: false,
    activationAllowed: false,
  });
  assert.equal(contract.eventPolicy.mappingDisposition, 'pending_mapping');
  assert.equal(contract.eventPolicy.rawValue, null);
  assert.equal(contract.eventPolicy.maxValue, null);
  assert.equal(contract.eventPolicy.normalizedValue, null);
  assert.equal(contract.identityAuthority.publicApiSessionAccepted, false);
  assert.equal(contract.identityAuthority.currentIdentityRpcConnected, false);
  assert.equal(contract.identityAuthority.currentSourceIdentityResolverConnected, false);
  assert.equal(contract.identityAuthority.sameBoundedCookieRequired, true);
  assert.equal(contract.identityAuthority.projectAcceptsOwnerUserKey, false);
  assert.equal(contract.identityAuthority.callerSuppliedOwnerUserKeyAccepted, false);
  assert.deepEqual(contract.identityAuthority.requiredDependencies, {
    identityRpc: { method: 'resolveSession', authority: 'positive_immutable_numeric_uc_user_id' },
    sourceIdentity: { method: 'resolveOwner', authority: 'authenticated_weibian_owner_key' },
  });
});

test('projects only a server-persisted first answer as a non-scoring pending event', async () => {
  const projected = await adapter().project({
    cookieHeader: COOKIE_A,
    serverReceipt: receipt(),
  });
  assert.equal(projected.mappingDisposition, 'pending_mapping');
  assert.match(projected.payloadHash, /^[a-f0-9]{64}$/);
  assert.equal(projected.event.schema, 'bdfz-learning-evidence-event-v2');
  assert.equal(projected.event.schemaVersion, 2);
  assert.equal(projected.event.userId, 343);
  assert.equal(projected.event.sourceEventId, EVENT_ID);
  assert.equal(projected.event.sourceAttemptId, EVENT_ID);
  assert.equal(projected.event.assessmentKind, 'trace');
  assert.equal(projected.event.scoringRole, 'none');
  assert.equal(projected.event.eligibilityStatus, 'non_scoring');
  assert.equal(projected.event.rawValue, null);
  assert.equal(projected.event.maxValue, null);
  assert.equal(projected.event.normalizedValue, null);
  assert.equal(projected.event.occurredAt, '2026-08-15T10:00:00.000Z');
  assert.equal(projected.event.academicYear, '2025-2026');
  assert.equal(projected.event.contractVersion, WEIBIAN_EVENT_V2_CANDIDATE_CONSTANTS.contractVersion);

  const serialized = JSON.stringify(projected.event);
  for (const forbidden of [
    'chosenOptionId', 'selectedOption', 'selected_option', 'correct', 'points',
    'score', 'userKey', 'user_key', 'slug', 'displayName', 'cookie',
  ]) {
    assert.equal(serialized.includes(`\"${forbidden}\"`), false, forbidden);
  }
});

test('both identity authorities receive the same bounded cookie before ledger lookup', async () => {
  const order = [];
  await adapter({ order }).project({
    cookieHeader: `theme=dark; ${COOKIE_A}; unrelated=value`,
    serverReceipt: receipt(),
  });
  assert.deepEqual(order, [
    ['identity', COOKIE_A],
    ['sourceIdentity', COOKIE_A],
    ['ledger', { eventId: EVENT_ID, ownerUserKey: OWNER_A }],
  ]);
});

test('hostile session-A owner-B request is rejected before either identity authority', async () => {
  const order = [];
  await expectCode(
    adapter({ order }).project({
      cookieHeader: COOKIE_A,
      ownerUserKey: OWNER_B,
      serverReceipt: receipt(),
    }),
    'project_input_field_forbidden',
  );
  assert.deepEqual(order, []);
});

test('same persisted first answer replay is deterministic', async () => {
  const candidate = adapter();
  const input = {
    cookieHeader: COOKIE_A,
    serverReceipt: receipt({ status: 'replayed' }),
  };
  const first = await candidate.project(input);
  const second = await candidate.project(input);
  assert.deepEqual(second, first);
});

test('already-recorded receipt projects the original canonical event id', async () => {
  const projected = await adapter().project({
    cookieHeader: COOKIE_A,
    serverReceipt: receipt({
      eventId: 'weibian_answer_00000002',
      canonicalEventId: EVENT_ID,
      status: 'already-recorded',
    }),
  });
  assert.equal(projected.event.sourceEventId, EVENT_ID);
  assert.equal(projected.event.sourceAttemptId, EVENT_ID);
});

test('public session response shape cannot become numeric identity authority', async () => {
  await expectCode(
    adapter({
      identityResult: {
        authenticated: true,
        user: { id: 343, slug: 'public-shape' },
      },
    }).project({
      cookieHeader: COOKIE_A,
      serverReceipt: receipt(),
    }),
    'named_identity_response_invalid',
  );
});

test('fetch-only public helper is rejected in favor of named RPC resolveSession', () => {
  assert.throws(
    () => createWeibianFirstAnswerEventV2Candidate({
      identityRpc: { fetch: async () => new Response('{}') },
      sourceIdentity: { resolveOwner: async () => sourceOwner() },
      verifiedAnswerLedger: { readByEventAndOwner: async () => row() },
    }),
    (error) => error.code === 'named_identity_rpc_required',
  );
});

test('factory requires the exact dual-authority dependency names', () => {
  assert.throws(
    () => createWeibianFirstAnswerEventV2Candidate({
      identityRpc: { resolveSession: async () => identity() },
      sourceIdentity: { resolveSession: async () => sourceOwner() },
      verifiedAnswerLedger: { readByEventAndOwner: async () => row() },
    }),
    (error) => error.code === 'source_identity_resolver_required',
  );
  assert.throws(
    () => createWeibianFirstAnswerEventV2Candidate({
      identityRpc: { resolveSession: async () => identity() },
      sourceIdentity: { resolveOwner: async () => sourceOwner() },
      verifiedAnswerLedger: { readByEventAndOwner: async () => row() },
      ownerUserKey: OWNER_B,
    }),
    (error) => error.code === 'candidate_dependency_field_forbidden',
  );
});

test('numeric identity must be a positive immutable number from the bound site', async () => {
  for (const identityResult of [
    { authenticated: true, sourceSiteKey: 'weibian', userId: '343' },
    identity(0),
    identity(-1),
    identity(1.5),
    { authenticated: true, sourceSiteKey: 'other-site', userId: 343 },
    { authenticated: false, sourceSiteKey: 'weibian', userId: 343 },
  ]) {
    await expectCode(
      adapter({ identityResult }).project({
        cookieHeader: COOKIE_A,
        serverReceipt: receipt(),
      }),
      'positive_immutable_uc_user_id_required',
    );
  }
});

test('identity RPC failures are explicit and never echo the cookie', async () => {
  const secretLikeCookie = 'bdfz_uc_session=test-only-never-echo';
  const order = [];
  let thrown;
  try {
    await adapter({ order, identityError: new Error('upstream unavailable') }).project({
      cookieHeader: secretLikeCookie,
      serverReceipt: receipt(),
    });
  } catch (error) {
    thrown = error;
  }
  assert.equal(thrown.code, 'named_identity_rpc_failed');
  assert.equal(String(thrown).includes(secretLikeCookie), false);
  assert.equal(thrown.cause, undefined);
  assert.deepEqual(order, [
    ['identity', secretLikeCookie],
    ['sourceIdentity', secretLikeCookie],
  ]);
});

test('source owner resolver failures are explicit, redacted, and cause-free', async () => {
  const secretLikeCookie = 'bdfz_uc_session=test-only-source-never-echo';
  const order = [];
  let thrown;
  try {
    await adapter({
      order,
      sourceIdentityError: new Error(`source failed for ${secretLikeCookie}`),
    }).project({
      cookieHeader: secretLikeCookie,
      serverReceipt: receipt(),
    });
  } catch (error) {
    thrown = error;
  }
  assert.equal(thrown.code, 'source_identity_resolver_failed');
  assert.equal(String(thrown).includes(secretLikeCookie), false);
  assert.equal(thrown.cause, undefined);
  assert.deepEqual(order, [
    ['identity', secretLikeCookie],
    ['sourceIdentity', secretLikeCookie],
  ]);
});

test('ledger failures expose neither request identity nor upstream cause', async () => {
  const secretLikeCookie = 'bdfz_uc_session=test-only-ledger-never-echo';
  let thrown;
  try {
    await adapter({
      ledgerError: new Error(`ledger failed for ${OWNER_A} ${secretLikeCookie}`),
    }).project({
      cookieHeader: secretLikeCookie,
      serverReceipt: receipt(),
    });
  } catch (error) {
    thrown = error;
  }
  assert.equal(thrown.code, 'verified_answer_ledger_read_failed');
  assert.equal(String(thrown).includes(secretLikeCookie), false);
  assert.equal(String(thrown).includes(OWNER_A), false);
  assert.equal(thrown.cause, undefined);
});

test('source owner resolver response is exact, site-bound, and authenticated', async () => {
  for (const [sourceIdentityResult, code] of [
    [{ authenticated: true, sourceSiteKey: 'weibian', ownerUserKey: 'not-a-key' }, 'source_owner_key_invalid'],
    [{ authenticated: true, sourceSiteKey: 'other-site', ownerUserKey: OWNER_A }, 'source_identity_response_invalid'],
    [{ authenticated: false, sourceSiteKey: 'weibian', ownerUserKey: OWNER_A }, 'source_identity_response_invalid'],
    [{ ...sourceOwner(), slug: 'unexpected' }, 'source_identity_response_invalid'],
  ]) {
    await expectCode(
      adapter({ sourceIdentityResult }).project({
        cookieHeader: COOKIE_A,
        serverReceipt: receipt(),
      }),
      code,
    );
  }
});

test('invalid or oversized session headers fail before identity transport', async () => {
  for (const cookieHeader of [
    '',
    'unrelated=value',
    `bdfz_uc_session=${'x'.repeat(4097)}`,
    `bdfz_uc_session=${'😀'.repeat(1100)}`,
    'bdfz_uc_session=a; bdfz_uc_session=b',
    'bdfz_uc_session=x\r\ny',
    'bdfz_uc_session=x\u0000y',
  ]) {
    const order = [];
    await expectCode(
      adapter({ order }).project({
        cookieHeader,
        serverReceipt: receipt(),
      }),
      'named_identity_cookie_invalid',
    );
    assert.deepEqual(order, []);
  }
});

test('cross-user ledger rows fail closed after owner-scoped lookup', async () => {
  await expectCode(
    adapter({ ledgerRow: row({ user_key: OWNER_B }) }).project({
      cookieHeader: COOKIE_A,
      serverReceipt: receipt(),
    }),
    'verified_first_answer_owner_mismatch',
  );
});

test('unrecorded, conflicting, or client-extended receipts are rejected', async () => {
  await expectCode(
    adapter().project({
      cookieHeader: COOKIE_A,
      serverReceipt: receipt({ status: 'conflict', recorded: false }),
    }),
    'server_receipt_not_recorded',
  );
  await expectCode(
    adapter().project({
      cookieHeader: COOKIE_A,
      serverReceipt: { ...receipt(), score: 100 },
    }),
    'server_receipt_field_forbidden',
  );
});

test('source semantic, result, receipt, and Beijing-day drift fail closed', async () => {
  const cases = [
    [row({ task_semantic_digest: 'not-a-digest' }), receipt(), 'verified_task_semantic_digest_invalid'],
    [row({ correct: 0, points: 1 }), receipt(), 'verified_result_inconsistent'],
    [row(), receipt({ points: 0, correct: false }), 'verified_first_answer_receipt_result_mismatch'],
    [row({ beijing_day: '2026-08-14' }), receipt({ beijingDay: '2026-08-14' }), 'verified_first_answer_receipt_result_mismatch'],
  ];
  for (const [ledgerRow, serverReceipt, code] of cases) {
    await expectCode(
      adapter({ ledgerRow }).project({
        cookieHeader: COOKIE_A,
        serverReceipt,
      }),
      code,
    );
  }
});

test('academic year follows server receipt time at the Beijing September boundary', async () => {
  const beforeMs = Date.parse('2026-08-31T15:59:00.000Z');
  const afterMs = Date.parse('2026-08-31T16:00:00.000Z');
  const before = await adapter({
    ledgerRow: row({ received_at_ms: beforeMs, beijing_day: '2026-08-31' }),
    nowMs: afterMs + 60_000,
  }).project({
    cookieHeader: COOKIE_A,
    serverReceipt: receipt({
      receivedAt: new Date(beforeMs).toISOString(),
      beijingDay: '2026-08-31',
    }),
  });
  const after = await adapter({
    ledgerRow: row({ received_at_ms: afterMs, beijing_day: '2026-09-01' }),
    nowMs: afterMs + 60_000,
  }).project({
    cookieHeader: COOKIE_A,
    serverReceipt: receipt({
      receivedAt: new Date(afterMs).toISOString(),
      beijingDay: '2026-09-01',
    }),
  });
  assert.equal(before.event.academicYear, '2025-2026');
  assert.equal(after.event.academicYear, '2026-2027');
});

test('concurrent requests retain no module-global user state', async () => {
  const candidate = createWeibianFirstAnswerEventV2Candidate({
    identityRpc: {
      async resolveSession(cookieHeader) {
        return identity(cookieHeader.includes('session-b') ? 9951 : 343);
      },
    },
    sourceIdentity: {
      async resolveOwner(cookieHeader) {
        return sourceOwner(cookieHeader === COOKIE_B ? OWNER_B : OWNER_A);
      },
    },
    verifiedAnswerLedger: {
      async readByEventAndOwner({ eventId, ownerUserKey }) {
        return row({ event_id: eventId, user_key: ownerUserKey });
      },
    },
    clock: () => NOW_MS,
  });
  const [a, b] = await Promise.all([
    candidate.project({
      cookieHeader: COOKIE_A,
      serverReceipt: receipt(),
    }),
    candidate.project({
      cookieHeader: COOKIE_B,
      serverReceipt: receipt({
        eventId: 'weibian_answer_00000002',
        canonicalEventId: 'weibian_answer_00000002',
      }),
    }),
  ]);
  assert.equal(a.event.userId, 343);
  assert.equal(b.event.userId, 9951);
  assert.notEqual(a.event.userId, b.event.userId);
});

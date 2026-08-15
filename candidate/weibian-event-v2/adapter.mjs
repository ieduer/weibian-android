const SOURCE_SYSTEM = 'weibian-content';
const SOURCE_SITE_KEY = 'weibian';
const CONTRACT_VERSION = 'weibian-first-answer-event-v2-candidate-v1';
const SOURCE_RELEASE_ID =
  'weibian-answer-events-v1:f17e5d54e10f34047fac70424e63e836dcf002ea';
const REGISTRY_VERSION = 'weibian-authored-task-bank-v1';
const MAPPING_VERSION = 'pending_mapping:weibian-first-answer-v1';
const SOURCE_URL = 'https://weibian.bdfz.net/';
const MAX_COOKIE_HEADER_BYTES = 4096;

const EVENT_ID_RE = /^[a-z0-9][a-z0-9_-]{7,99}$/;
const USER_KEY_RE = /^[a-f0-9]{64}$/;
const TASK_ID_RE = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$/;
const OPTION_ID_RE = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,39}$/;
const CONTENT_VERSION_RE = /^[a-f0-9]{16}$/;
const SHA256_RE = /^[a-f0-9]{64}$/;
const BEIJING_DAY_RE = /^\d{4}-\d{2}-\d{2}$/;
const RECORDED_STATUSES = new Set(['accepted', 'replayed', 'already-recorded']);
const RECEIPT_KEYS = new Set([
  'eventId',
  'canonicalEventId',
  'taskId',
  'status',
  'recorded',
  'correct',
  'points',
  'receivedAt',
  'beijingDay',
]);
const FACTORY_DEPENDENCY_KEYS = new Set([
  'identityRpc',
  'sourceIdentity',
  'verifiedAnswerLedger',
  'clock',
]);
const PROJECT_INPUT_KEYS = new Set(['cookieHeader', 'serverReceipt']);
const NAMED_IDENTITY_KEYS = new Set([
  'authenticated',
  'sourceSiteKey',
  'userId',
]);
const SOURCE_IDENTITY_KEYS = new Set([
  'authenticated',
  'sourceSiteKey',
  'ownerUserKey',
]);

export class WeibianEventV2CandidateError extends Error {
  constructor(code) {
    super(code);
    this.name = 'WeibianEventV2CandidateError';
    this.code = code;
  }
}

function fail(code) {
  throw new WeibianEventV2CandidateError(code);
}

function object(value, code) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail(code);
  return value;
}

function exactString(value, pattern, code) {
  if (typeof value !== 'string' || !pattern.test(value)) fail(code);
  return value;
}

function safeInteger(value, minimum, maximum, code) {
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) fail(code);
  return value;
}

function stableCanonical(value) {
  if (Array.isArray(value)) return `[${value.map(stableCanonical).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${stableCanonical(value[key])}`)
      .join(',')}}`;
  }
  return JSON.stringify(value);
}

async function sha256Hex(value) {
  const digest = await crypto.subtle.digest(
    'SHA-256',
    new TextEncoder().encode(String(value)),
  );
  return [...new Uint8Array(digest)]
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('');
}

function beijingDateParts(timestampMs) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date(timestampMs));
  const pick = (type) => parts.find((part) => part.type === type)?.value || '';
  return Object.freeze({
    year: Number(pick('year')),
    month: Number(pick('month')),
    day: Number(pick('day')),
    dayKey: `${pick('year')}-${pick('month')}-${pick('day')}`,
  });
}

function academicYearFor(timestampMs) {
  const { year, month } = beijingDateParts(timestampMs);
  const start = month >= 9 ? year : year - 1;
  return `${start}-${start + 1}`;
}

function boundedSessionCookie(cookieHeader) {
  if (
    typeof cookieHeader !== 'string'
    || cookieHeader.length < 1
    || new TextEncoder().encode(cookieHeader).byteLength > MAX_COOKIE_HEADER_BYTES
    || /[\x00-\x1f\x7f]/.test(cookieHeader)
  ) {
    fail('named_identity_cookie_invalid');
  }
  const sessionCookies = cookieHeader
    .split(';')
    .map((part) => part.trim())
    .filter((part) => part.startsWith('bdfz_uc_session='));
  if (
    sessionCookies.length !== 1
    || !/^bdfz_uc_session=[^;\s]+$/.test(sessionCookies[0])
  ) {
    fail('named_identity_cookie_invalid');
  }
  // Strip unrelated cookies so both authorities parse one identical,
  // unambiguous request credential.
  return sessionCookies[0];
}

function validateServerReceipt(raw) {
  const receipt = object(raw, 'server_receipt_required');
  if (Object.keys(receipt).some((key) => !RECEIPT_KEYS.has(key))) {
    fail('server_receipt_field_forbidden');
  }
  const eventId = exactString(receipt.eventId, EVENT_ID_RE, 'server_receipt_event_invalid');
  const canonicalEventId = exactString(
    receipt.canonicalEventId,
    EVENT_ID_RE,
    'server_receipt_canonical_event_invalid',
  );
  const taskId = exactString(receipt.taskId, TASK_ID_RE, 'server_receipt_task_invalid');
  if (!RECORDED_STATUSES.has(receipt.status) || receipt.recorded !== true) {
    fail('server_receipt_not_recorded');
  }
  if (receipt.status !== 'already-recorded' && eventId !== canonicalEventId) {
    fail('server_receipt_replay_identity_mismatch');
  }
  if (typeof receipt.correct !== 'boolean') fail('server_receipt_verdict_invalid');
  safeInteger(receipt.points, 0, 1, 'server_receipt_points_invalid');
  if (receipt.points !== (receipt.correct ? 1 : 0)) fail('server_receipt_result_inconsistent');
  if (typeof receipt.receivedAt !== 'string' || !Number.isFinite(Date.parse(receipt.receivedAt))) {
    fail('server_receipt_time_invalid');
  }
  exactString(receipt.beijingDay, BEIJING_DAY_RE, 'server_receipt_day_invalid');
  return Object.freeze({
    eventId,
    canonicalEventId,
    taskId,
    status: receipt.status,
    recorded: true,
    correct: receipt.correct,
    points: receipt.points,
    receivedAt: receipt.receivedAt,
    beijingDay: receipt.beijingDay,
  });
}

function validatePersistedFirstAnswer(raw, ownerUserKey, receipt, nowMs) {
  const row = object(raw, 'verified_first_answer_row_required');
  const eventId = exactString(row.event_id, EVENT_ID_RE, 'verified_event_id_invalid');
  const userKey = exactString(row.user_key, USER_KEY_RE, 'verified_owner_key_invalid');
  const taskId = exactString(row.canonical_task_id, TASK_ID_RE, 'verified_task_id_invalid');
  const chapterId = safeInteger(row.chapter_id, 1, 541, 'verified_chapter_id_invalid');
  const contentVersion = exactString(
    row.content_version,
    CONTENT_VERSION_RE,
    'verified_content_version_invalid',
  );
  const taskSemanticDigest = exactString(
    row.task_semantic_digest,
    SHA256_RE,
    'verified_task_semantic_digest_invalid',
  );
  const selectedOption = exactString(
    row.selected_option,
    OPTION_ID_RE,
    'verified_selected_option_invalid',
  );
  const correct = safeInteger(row.correct, 0, 1, 'verified_correct_invalid');
  const points = safeInteger(row.points, 0, 1, 'verified_points_invalid');
  const receivedAtMs = safeInteger(
    row.received_at_ms,
    1,
    Number.MAX_SAFE_INTEGER,
    'verified_received_time_invalid',
  );
  const beijingDay = exactString(
    row.beijing_day,
    BEIJING_DAY_RE,
    'verified_beijing_day_invalid',
  );
  if (receivedAtMs > nowMs + 5 * 60 * 1000) fail('verified_received_time_future');
  if (correct !== points) fail('verified_result_inconsistent');
  if (userKey !== ownerUserKey) fail('verified_first_answer_owner_mismatch');
  if (eventId !== receipt.canonicalEventId || taskId !== receipt.taskId) {
    fail('verified_first_answer_receipt_identity_mismatch');
  }
  if (
    Boolean(correct) !== receipt.correct
    || points !== receipt.points
    || new Date(receivedAtMs).toISOString() !== receipt.receivedAt
    || beijingDay !== receipt.beijingDay
    || beijingDateParts(receivedAtMs).dayKey !== beijingDay
  ) {
    fail('verified_first_answer_receipt_result_mismatch');
  }
  return Object.freeze({
    eventId,
    userKey,
    taskId,
    chapterId,
    contentVersion,
    taskSemanticDigest,
    selectedOption,
    correct,
    points,
    receivedAtMs,
    beijingDay,
  });
}

function validateNamedIdentity(raw) {
  const session = object(raw, 'named_identity_response_invalid');
  if (
    Object.keys(session).length !== NAMED_IDENTITY_KEYS.size
    || Object.keys(session).some((key) => !NAMED_IDENTITY_KEYS.has(key))
  ) {
    fail('named_identity_response_invalid');
  }
  if (
    session.authenticated !== true
    || session.sourceSiteKey !== SOURCE_SITE_KEY
    || typeof session.userId !== 'number'
    || !Number.isSafeInteger(session.userId)
    || session.userId <= 0
  ) {
    fail('positive_immutable_uc_user_id_required');
  }
  return Object.freeze({
    authenticated: true,
    sourceSiteKey: SOURCE_SITE_KEY,
    userId: session.userId,
  });
}

function validateSourceIdentity(raw) {
  const sourceOwner = object(raw, 'source_identity_response_invalid');
  if (
    Object.keys(sourceOwner).length !== SOURCE_IDENTITY_KEYS.size
    || Object.keys(sourceOwner).some((key) => !SOURCE_IDENTITY_KEYS.has(key))
    || sourceOwner.authenticated !== true
    || sourceOwner.sourceSiteKey !== SOURCE_SITE_KEY
  ) {
    fail('source_identity_response_invalid');
  }
  return Object.freeze({
    authenticated: true,
    sourceSiteKey: SOURCE_SITE_KEY,
    ownerUserKey: exactString(
      sourceOwner.ownerUserKey,
      USER_KEY_RE,
      'source_owner_key_invalid',
    ),
  });
}

async function resolveNamedIdentity(identityRpc, cookie) {
  let result;
  try {
    result = await identityRpc.resolveSession(cookie);
  } catch {
    // Do not retain an upstream error as `cause`: an implementation error may
    // include the session header. The stable code is the complete boundary.
    fail('named_identity_rpc_failed');
  }
  return validateNamedIdentity(result);
}

async function resolveSourceIdentity(sourceIdentity, cookie) {
  let result;
  try {
    result = await sourceIdentity.resolveOwner(cookie);
  } catch {
    // The source resolver is allowed to inspect the request credential. Never
    // retain its error or attach it as a cause.
    fail('source_identity_resolver_failed');
  }
  return validateSourceIdentity(result);
}

async function projectVerifiedFirstAnswerEventV2({
  persistedRow,
  ownerUserKey,
  serverReceipt,
  identity,
  nowMs = Date.now(),
}) {
  const currentTime = safeInteger(
    nowMs,
    1,
    Number.MAX_SAFE_INTEGER,
    'projection_clock_invalid',
  );
  const legacyOwnerKey = exactString(
    ownerUserKey,
    USER_KEY_RE,
    'authenticated_legacy_owner_key_invalid',
  );
  const receipt = validateServerReceipt(serverReceipt);
  const numericIdentity = validateNamedIdentity(identity);
  const source = validatePersistedFirstAnswer(
    persistedRow,
    legacyOwnerKey,
    receipt,
    currentTime,
  );
  const occurredAt = new Date(source.receivedAtMs).toISOString();
  const event = Object.freeze({
    schema: 'bdfz-learning-evidence-event-v2',
    schemaVersion: 2,
    sourceSystem: SOURCE_SYSTEM,
    sourceSiteKey: SOURCE_SITE_KEY,
    sourceEventId: source.eventId,
    sourceAttemptId: source.eventId,
    supersedesSourceAttemptId: '',
    contractVersion: CONTRACT_VERSION,
    sourceReleaseId: SOURCE_RELEASE_ID,
    canonicalUnitId: `weibian:authored-task:${source.taskId}`,
    resourceVersion: `sha256:${source.taskSemanticDigest}`,
    sourceVersion: source.contentVersion,
    registryVersion: REGISTRY_VERSION,
    mappingVersion: MAPPING_VERSION,
    userId: numericIdentity.userId,
    academicYear: academicYearFor(source.receivedAtMs),
    occurredAt,
    interactionKey: 'authored_first_answer',
    eventType: 'authored_first_answer_verified',
    assessmentKind: 'trace',
    scoringRole: 'none',
    eligibilityStatus: 'non_scoring',
    verificationMethod: 'server_recomputed_first_answer_v1',
    dimensionKey: 'practice',
    resourceKey: `weibian:authored-task:${source.taskId}`,
    rawValue: null,
    maxValue: null,
    normalizedValue: null,
    sourceUrl: SOURCE_URL,
    sourcePayloadRef: `weibian-answer-event:${source.eventId}`,
    classSessionId: '',
    lessonPhase: 'practice',
    attemptNo: 1,
    summary: Object.freeze({
      itemGroup: 'authored-question',
      eventType: 'authored_first_answer_verified',
    }),
    facets: Object.freeze([
      Object.freeze({ key: 'source_contract', value: 'weibian-answer-events-v1' }),
    ]),
  });
  const deliveryIdentity = [
    event.sourceSiteKey,
    event.contractVersion,
    event.sourceReleaseId,
    event.canonicalUnitId,
    event.resourceVersion,
    event.sourceAttemptId,
  ].join('\u001f');
  return Object.freeze({
    event,
    mappingDisposition: 'pending_mapping',
    deliveryIdentity,
    payloadHash: await sha256Hex(stableCanonical(event)),
  });
}

export function createWeibianFirstAnswerEventV2Candidate(rawDependencies) {
  const dependencies = object(rawDependencies, 'candidate_dependencies_required');
  if (Object.keys(dependencies).some((key) => !FACTORY_DEPENDENCY_KEYS.has(key))) {
    fail('candidate_dependency_field_forbidden');
  }
  const {
    identityRpc,
    sourceIdentity,
    verifiedAnswerLedger,
    clock = Date.now,
  } = dependencies;
  if (!identityRpc || typeof identityRpc.resolveSession !== 'function') {
    fail('named_identity_rpc_required');
  }
  if (!sourceIdentity || typeof sourceIdentity.resolveOwner !== 'function') {
    fail('source_identity_resolver_required');
  }
  if (!verifiedAnswerLedger || typeof verifiedAnswerLedger.readByEventAndOwner !== 'function') {
    fail('verified_answer_ledger_required');
  }
  if (typeof clock !== 'function') fail('projection_clock_required');
  return Object.freeze({
    async project(rawInput) {
      const input = object(rawInput, 'project_input_required');
      if (
        Object.keys(input).length !== PROJECT_INPUT_KEYS.size
        || Object.keys(input).some((key) => !PROJECT_INPUT_KEYS.has(key))
      ) {
        fail('project_input_field_forbidden');
      }
      const receipt = validateServerReceipt(input.serverReceipt);
      const cookie = boundedSessionCookie(input.cookieHeader);
      // Both authorities receive the exact same bounded credential and finish
      // before any owner-scoped ledger lookup. No request field can select the
      // ledger owner.
      const [identity, sourceOwner] = await Promise.all([
        resolveNamedIdentity(identityRpc, cookie),
        resolveSourceIdentity(sourceIdentity, cookie),
      ]);
      let row;
      try {
        row = await verifiedAnswerLedger.readByEventAndOwner(Object.freeze({
          eventId: receipt.canonicalEventId,
          ownerUserKey: sourceOwner.ownerUserKey,
        }));
      } catch {
        // The ledger implementation may include a query value in its error;
        // expose only the stable candidate error code.
        fail('verified_answer_ledger_read_failed');
      }
      if (!row) fail('verified_first_answer_not_found');
      return projectVerifiedFirstAnswerEventV2({
        persistedRow: row,
        ownerUserKey: sourceOwner.ownerUserKey,
        serverReceipt: receipt,
        identity,
        nowMs: clock(),
      });
    },
  });
}

export const WEIBIAN_EVENT_V2_CANDIDATE_CONSTANTS = Object.freeze({
  sourceSystem: SOURCE_SYSTEM,
  sourceSiteKey: SOURCE_SITE_KEY,
  contractVersion: CONTRACT_VERSION,
  sourceReleaseId: SOURCE_RELEASE_ID,
  registryVersion: REGISTRY_VERSION,
  mappingVersion: MAPPING_VERSION,
});

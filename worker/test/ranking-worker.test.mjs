import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import worker, {
  publicRankingEntry,
  rankingEventErrorStatus,
} from '../src/index.js';
import {
  ANSWER_EVENT_SCHEMA,
  buildAuthoredTaskIndex,
  MAX_AUTHORED_TASKS,
} from '../src/ranking.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const contentPath = path.resolve(here, '..', 'public', 'content.json');
const manifestPath = path.resolve(here, '..', 'public', 'manifest.json');
const migrationPath = path.resolve(
  here,
  '..',
  'migrations',
  '0002_verified_answer_rankings.sql',
);

class SqliteD1 {
  constructor(migrationSql) {
    this.sqlite = new DatabaseSync(':memory:');
    this.sqlite.exec(migrationSql);
  }

  get rows() {
    return this.sqlite
      .prepare('SELECT * FROM weibian_answer_events_v2 ORDER BY event_id')
      .all();
  }

  prepare(sql) {
    const statement = this.sqlite.prepare(sql);
    return {
      values: [],
      bind(...values) {
        this.values = values;
        return this;
      },
      async first() {
        return statement.get(...this.values) || null;
      },
      async run() {
        const result = statement.run(...this.values);
        return {
          success: true,
          meta: { changes: Number(result.changes || 0) },
        };
      },
      async all() {
        return { results: statement.all(...this.values) };
      },
    };
  }
}

async function fixture() {
  const [bytes, manifestBytes, migrationSql] = await Promise.all([
    readFile(contentPath),
    readFile(manifestPath),
    readFile(migrationPath, 'utf8'),
  ]);
  const database = new SqliteD1(migrationSql);
  return {
    database,
    env: {
      DB: database,
      RANKING_PEPPER: 'p'.repeat(32),
      USER_CENTER: {
        async fetch(request) {
          const authenticated = request.headers.get('Cookie')?.includes('test-session');
          return Response.json(
            authenticated
              ? { authenticated: true, user: { slug: 'test-account' } }
              : { authenticated: false },
          );
        },
      },
      ASSETS: {
        async fetch(request) {
          const url = request instanceof URL ? request : new URL(request.url);
          return url.pathname === '/manifest.json'
            ? new Response(manifestBytes, {
                headers: { 'Content-Type': 'application/json' },
              })
            : new Response('not found', { status: 404 });
        },
      },
      CONTENT_R2: {
        async get() {
          return {
            size: bytes.length,
            async arrayBuffer() {
              return bytes.buffer.slice(
                bytes.byteOffset,
                bytes.byteOffset + bytes.byteLength,
              );
            },
          };
        },
      },
    },
  };
}

function event(eventId, chosenOptionId, extra = {}) {
  return {
    eventId,
    contentVersion: 'fc68413c7b70da0e',
    taskId: 'cm-1-1a',
    chapterId: 1,
    chosenOptionId,
    ...extra,
  };
}

function request(events, cookie = 'bdfz_uc_session=test-session') {
  return new Request('https://weibian.bdfz.net/api/ranking-events', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Cookie: cookie,
    },
    body: JSON.stringify({ schema: ANSWER_EVENT_SCHEMA, events }),
  });
}

test('the immutable production bundle has exactly the reviewed authored bank', async () => {
  const content = JSON.parse(await readFile(contentPath, 'utf8'));
  assert.equal(buildAuthoredTaskIndex(content).size, MAX_AUTHORED_TASKS);
});

test('the production migration has required columns, uniqueness and query indexes', async () => {
  const migrationSql = await readFile(migrationPath, 'utf8');
  const database = new SqliteD1(migrationSql);
  const columns = database.sqlite
    .prepare('PRAGMA table_info(weibian_answer_events_v2)')
    .all()
    .map((row) => row.name);
  assert.deepEqual(columns, [
    'event_id',
    'user_key',
    'canonical_task_id',
    'chapter_id',
    'content_version',
    'task_semantic_digest',
    'selected_option',
    'correct',
    'points',
    'received_at_ms',
    'beijing_day',
    'created_at',
  ]);
  const indexes = database.sqlite
    .prepare('PRAGMA index_list(weibian_answer_events_v2)')
    .all();
  assert.ok(
    indexes.some((row) => row.name === 'idx_weibian_answer_rankings_total'),
  );
  assert.ok(
    indexes.some((row) => row.name === 'idx_weibian_answer_rankings_daily'),
  );
  assert.ok(indexes.filter((row) => Number(row.unique) === 1).length >= 2);
});

test('legacy ranking compatibility never relabels correct answers as completed chapters', () => {
  const entry = publicRankingEntry(
    {
      user_key: '0123456789abcdef',
      position: 1,
      total_points: 3,
      daily_points: 2,
      verified_correct_answers: 3,
      answered_questions: 4,
      active_chapters: 2,
    },
    '',
    true,
  );
  assert.equal(entry.completedChapters, 0);
  assert.equal(entry.activeChapters, 2);
  assert.equal(entry.totalPoints, 3);
});

test('server content failures remain retryable while client event failures are rejected', () => {
  assert.equal(
    rankingEventErrorStatus(new Error('invalid-authored-task-bank-size')),
    503,
  );
  assert.equal(
    rankingEventErrorStatus(new Error('ranking-content-hash-mismatch')),
    503,
  );
  assert.equal(rankingEventErrorStatus(new Error('invalid-chosen-option')), 400);
  const conflict = new Error('answer-event-id-conflict');
  conflict.status = 409;
  assert.equal(rankingEventErrorStatus(conflict), 409);
});

test('ranking health fails closed on missing secret and verifies D1 plus exact R2 content', async () => {
  const { env: badHashEnv } = await fixture();
  badHashEnv.CONTENT_R2 = {
    async get() {
      const bytes = new Uint8Array(871_333);
      return {
        size: bytes.byteLength,
        async arrayBuffer() {
          return bytes.buffer;
        },
      };
    },
  };
  const badHash = await worker.fetch(
    new Request('https://weibian.bdfz.net/api/rankings/health'),
    badHashEnv,
  );
  assert.equal(badHash.status, 503);

  const { env } = await fixture();
  const healthy = await worker.fetch(
    new Request('https://weibian.bdfz.net/api/rankings/health'),
    env,
  );
  assert.equal(healthy.status, 200);
  const payload = await healthy.json();
  assert.equal(payload.ok, true);
  assert.equal(payload.eligibleTaskCount, MAX_AUTHORED_TASKS);
  assert.equal(payload.contentVersion, 'fc68413c7b70da0e');

  for (const brokenEnv of [
    { ...env, RANKING_PEPPER: '' },
    { ...env, DB: undefined },
    {
      ...env,
      DB: {
        prepare() {
          return {
            async first() {
              throw new Error('no such table: weibian_answer_events_v2');
            },
          };
        },
      },
    },
    { ...env, USER_CENTER: undefined },
    { ...env, CONTENT_R2: undefined },
    { ...env, ASSETS: undefined },
  ]) {
    const unhealthy = await worker.fetch(
      new Request('https://weibian.bdfz.net/api/rankings/health'),
      brokenEnv,
    );
    assert.equal(unhealthy.status, 503);
    assert.equal((await unhealthy.json()).error, 'rankings-unhealthy');
  }
});

test('first authored answer is server-validated, idempotent and immutable', async () => {
  const { env, database } = await fixture();

  const first = await worker.fetch(
    request([event('weibian_answer_first0001', 'b')]),
    env,
  );
  assert.equal(first.status, 200);
  const firstPayload = await first.json();
  assert.equal(firstPayload.receipts[0].status, 'accepted');
  assert.equal(firstPayload.receipts[0].eventId, 'weibian_answer_first0001');
  assert.equal(
    firstPayload.receipts[0].canonicalEventId,
    'weibian_answer_first0001',
  );
  assert.equal(firstPayload.receipts[0].correct, false);
  assert.equal(firstPayload.receipts[0].points, 0);
  assert.equal(database.rows.length, 1);

  const replay = await worker.fetch(
    request([event('weibian_answer_first0001', 'b')]),
    env,
  );
  assert.equal(replay.status, 200);
  assert.equal((await replay.json()).receipts[0].status, 'replayed');
  assert.equal(database.rows.length, 1);

  const changedEventId = await worker.fetch(
    request([event('weibian_answer_second001', 'a')]),
    env,
  );
  assert.equal(changedEventId.status, 200);
  const changedPayload = await changedEventId.json();
  assert.equal(changedPayload.receipts[0].status, 'already-recorded');
  assert.equal(changedPayload.receipts[0].eventId, 'weibian_answer_second001');
  assert.equal(
    changedPayload.receipts[0].canonicalEventId,
    'weibian_answer_first0001',
  );
  assert.equal(changedPayload.receipts[0].correct, false);
  assert.equal(changedPayload.receipts[0].points, 0);
  assert.equal(database.rows.length, 1);
});

test('a conflicting event cannot poison earlier receipts in the same batch', async () => {
  const { env, database } = await fixture();
  const content = JSON.parse(await readFile(contentPath, 'utf8'));
  const secondTask = content.bank[1];
  database.sqlite.prepare(
    `INSERT INTO weibian_answer_events_v2 (
       event_id, user_key, canonical_task_id, chapter_id, content_version,
       task_semantic_digest, selected_option, correct, points,
       received_at_ms, beijing_day
     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  ).run(
    'weibian_answer_conflict1',
    'another-user',
    'another-task',
    1,
    'fc68413c7b70da0e',
    '0'.repeat(64),
    'a',
    0,
    0,
    Date.now(),
    '2026-07-30',
  );
  const events = [
    event('weibian_answer_batch0001', 'a'),
    event(
      'weibian_answer_conflict1',
      secondTask.options[0].id,
      {
        taskId: secondTask.id,
        chapterId: secondTask.refs[0],
      },
    ),
  ];

  const first = await worker.fetch(request(events), env);
  assert.equal(first.status, 200);
  const firstPayload = await first.json();
  assert.equal(firstPayload.receipts[0].status, 'accepted');
  assert.equal(firstPayload.receipts[1].status, 'conflict');
  assert.equal(firstPayload.receipts[1].recorded, false);
  assert.equal(
    firstPayload.receipts[1].eventId,
    'weibian_answer_conflict1',
  );
  assert.equal(database.rows.length, 2);

  const replay = await worker.fetch(request(events), env);
  assert.equal(replay.status, 200);
  const replayPayload = await replay.json();
  assert.equal(replayPayload.receipts[0].status, 'replayed');
  assert.equal(replayPayload.receipts[1].status, 'conflict');
  assert.equal(database.rows.length, 2);
});

test('missing identity and client-supplied result fields fail before D1 mutation', async () => {
  const { env, database } = await fixture();

  const anonymous = await worker.fetch(
    request([event('weibian_answer_anon00001', 'a')], ''),
    env,
  );
  assert.equal(anonymous.status, 401);
  assert.equal(database.rows.length, 0);

  const selfScored = await worker.fetch(
    request([event('weibian_answer_score0001', 'a', { points: 100 })]),
    env,
  );
  assert.equal(selfScored.status, 400);
  assert.equal((await selfScored.json()).error, 'answer-event-rejected');
  assert.equal(database.rows.length, 0);
});

test('malformed and overlong authenticated slugs fail closed before D1 mutation', async () => {
  for (const slug of [{ nested: true }, 'x'.repeat(97)]) {
    const { env, database } = await fixture();
    env.USER_CENTER = {
      async fetch(request) {
        return request.headers.get('Cookie')
          ? Response.json({ authenticated: true, user: { slug } })
          : Response.json({ authenticated: false });
      },
    };
    const response = await worker.fetch(
      request([event('weibian_answer_badslug01', 'a')]),
      env,
    );
    assert.equal(response.status, 401);
    assert.equal(database.rows.length, 0);
  }
});

test('authentication forwards only the exact User Center session cookie', async () => {
  const { env } = await fixture();
  let forwardedCookie = '';
  env.USER_CENTER = {
    async fetch(request) {
      forwardedCookie = request.headers.get('Cookie') || '';
      return Response.json({
        authenticated: true,
        user: { slug: 'test-account' },
      });
    },
  };
  const response = await worker.fetch(
    new Request('https://weibian.bdfz.net/api/ranking-events', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Cookie: 'other=private; bdfz_uc_session=test-session; local=private',
      },
      body: JSON.stringify({
        schema: ANSWER_EVENT_SCHEMA,
        events: [event('weibian_answer_cookie001', 'a')],
      }),
    }),
    env,
  );
  assert.equal(response.status, 200);
  assert.equal(forwardedCookie, 'bdfz_uc_session=test-session');
});

test('missing Content-Length is streamed safely and oversized bodies are rejected', async () => {
  const { env, database } = await fixture();
  const smallBody = JSON.stringify({
    schema: ANSWER_EVENT_SCHEMA,
    events: [event('weibian_answer_stream001', 'a')],
  });
  const small = await worker.fetch(
    new Request('https://weibian.bdfz.net/api/ranking-events', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Cookie: 'bdfz_uc_session=test-session',
      },
      body: smallBody,
    }),
    env,
  );
  assert.equal(small.status, 200);
  assert.equal(database.rows.length, 1);

  const oversized = await worker.fetch(
    new Request('https://weibian.bdfz.net/api/ranking-events', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Cookie: 'bdfz_uc_session=test-session',
      },
      body: `{"padding":"${'x'.repeat(40 * 1024)}"}`,
    }),
    env,
  );
  assert.equal(oversized.status, 400);
  assert.equal(database.rows.length, 1);
});

test('real SQLite aggregation serves truthful legacy and v2 response shapes', async () => {
  const { env } = await fixture();
  const accepted = await worker.fetch(
    request([event('weibian_answer_board0001', 'a')]),
    env,
  );
  assert.equal(accepted.status, 200);

  const v2 = await worker.fetch(
    new Request('https://weibian.bdfz.net/api/rankings/v2', {
      headers: { Cookie: 'bdfz_uc_session=test-session' },
    }),
    env,
  );
  assert.equal(v2.status, 200);
  const v2Payload = await v2.json();
  assert.equal(v2Payload.schemaVersion, 'weibian-rankings-v2');
  assert.equal(v2Payload.total.length, 1);
  assert.equal(v2Payload.total[0].verifiedCorrectAnswers, 1);
  assert.equal(v2Payload.total[0].verifiedAnsweredQuestions, 1);
  assert.equal(v2Payload.total[0].activeChapters, 1);
  assert.equal(v2Payload.total[0].isMe, true);
  assert.match(v2Payload.total[0].displayName, /^学子·[A-F0-9]{8}$/);

  const legacy = await worker.fetch(
    new Request('https://weibian.bdfz.net/api/rankings'),
    env,
  );
  assert.equal(legacy.status, 200);
  const legacyPayload = await legacy.json();
  assert.equal(legacyPayload.schemaVersion, 'weibian-rankings-v1');
  assert.equal(legacyPayload.total[0].completedChapters, 0);
  assert.equal(legacyPayload.total[0].activeChapters, 1);
  assert.equal(legacyPayload.total[0].verifiedCorrectAnswers, undefined);
});

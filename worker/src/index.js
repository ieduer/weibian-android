/**
 * 韦编内容接口 —— weibian.bdfz.net
 *
 * 负责两条项目自有边界：下发版本化内容包，以及用同一份不可变内容在服务端
 * 核验人工精编题的原始作答事件并派生匿名学习榜。身份仍由 User Center 负责。
 *
 * 端点：
 *   GET /api/content/manifest   内容清单（版本、sha256、体积、条目数）
 *   GET /api/content/bundles/<CONTENT_VERSION>.json  不可变完整内容包
 *   GET /api/content/bundle     旧客户端兼容入口（不可长期缓存）
 *   GET|POST /api/rankings      code 3 兼容榜读取（POST 不再接收客户端分数）
 *   GET /api/rankings/v2        服务端核验作答榜
 *   POST /api/ranking-events    当前已登录用户的原始人工题作答 outbox
 *   GET /api/health             健康探针
 *
 * 刻意不做的事：
 *   · 不保存身份、Cookie、自由文本或 User Center 一般进度；
 *     排行 D1 只保存 HMAC 假名、稳定题号、所选 option、服务端判定与时间；
 *   · 不代理 AI —— 一律走 apis.bdfz.net 统一网关，本处不持有任何模型密钥。
 *
 * 当前 manifest 与旧客户端兼容 bundle 跟随 Worker Assets；已审核版本的
 * 不可变完整包由 R2 永久保存，并经 content-releases.js exact allowlist 暴露。
 * Worker 回滚恢复 manifest/allowlist，不能删除或覆盖已经发布的 R2 对象。
 */

import {
  ANSWER_EVENT_SCHEMA,
  beijingDayKey,
  buildAuthoredTaskIndex,
  MAX_AUTHORED_TASKS,
  MAX_EVENT_BODY_BYTES,
  parseAnswerEventBatch,
  RANKING_SCHEMA,
  requireCompleteAuthoredTaskIndex,
  rankForPoints,
  requireRankingPepper,
  validateAuthoredAnswer,
} from './ranking.js';
import {
  contentReleaseForVersion,
  deltaObjectKey,
} from './content-releases.js';

const CACHE_IMMUTABLE = 'public, max-age=31536000, immutable';
const CACHE_MANIFEST = 'public, max-age=300';
const CACHE_MUTABLE = 'public, max-age=0, must-revalidate';
const USER_CENTER_ORIGIN = 'https://my.bdfz.net';
const RANKING_TABLE = 'weibian_answer_events_v2';
const MAX_RANKING_LIMIT = 30;

/** App 与站点都可能来取内容，内容本身是公开资料，允许跨源读取。 */
const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, HEAD, POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type',
  'Access-Control-Max-Age': '86400',
};

function json(body, init = {}) {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      ...CORS,
      ...(init.headers || {}),
    },
  });
}

async function readAsset(env, path) {
  const response = await env.ASSETS.fetch(new URL(path, 'https://weibian.bdfz.net'));
  if (!response.ok) return null;
  return response;
}

function sessionCookieHeader(request) {
  const raw = (request.headers.get('Cookie') || '').slice(0, 4096);
  let sessionCookieSeen = false;
  for (const segment of raw.split(';')) {
    const cookie = segment.trim();
    if (!cookie.startsWith('bdfz_uc_session=')) continue;
    sessionCookieSeen = true;
    const value = cookie.slice('bdfz_uc_session='.length);
    if (value && value.length <= 2048 && !/[\r\n;]/.test(value)) {
      return `bdfz_uc_session=${value}`;
    }
  }
  return sessionCookieSeen ? null : '';
}

function boundedRankingLimit(url) {
  const parsed = Number(url.searchParams.get('limit') || 20);
  return Number.isFinite(parsed)
    ? Math.min(MAX_RANKING_LIMIT, Math.max(1, Math.trunc(parsed)))
    : 20;
}

async function userCenterJson(env, path, cookie) {
  const response = await env.USER_CENTER.fetch(
    new Request(`${USER_CENTER_ORIGIN}${path}`, {
      headers: { Accept: 'application/json', Cookie: cookie },
    }),
  );
  if (!response.ok) throw new Error(`user-center-${response.status}`);
  return response.json();
}

function sessionSlug(payload) {
  if (
    !payload ||
    payload.authenticated !== true ||
    !payload.user ||
    typeof payload.user !== 'object' ||
    typeof payload.user.slug !== 'string'
  ) {
    return '';
  }
  const slug = payload.user.slug.trim();
  return slug.length >= 1 && slug.length <= 96 ? slug : '';
}

async function hmacUserKey(slug, pepper) {
  const encoder = new TextEncoder();
  const key = await crypto.subtle.importKey(
    'raw',
    encoder.encode(pepper),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign'],
  );
  const signature = await crypto.subtle.sign(
    'HMAC',
    key,
    encoder.encode(`weibian-ranking-v2\u0000${slug}`),
  );
  return [...new Uint8Array(signature)]
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('');
}

async function authenticateRanking(request, env) {
  const cookie = sessionCookieHeader(request);
  if (cookie === null) return { invalid: true };
  if (!cookie) return null;
  const pepper = requireRankingPepper(env.RANKING_PEPPER);
  let session;
  try {
    session = await userCenterJson(env, '/api/session', cookie);
  } catch (error) {
    if (/^user-center-(?:401|403)$/.test(String(error?.message || ''))) {
      return { invalid: true };
    }
    throw error;
  }
  const slug = sessionSlug(session);
  if (!slug) return { invalid: true };
  return {
    cookie,
    userKey: await hmacUserKey(slug, pepper),
  };
}

function publicRankingName(userKey) {
  return `学子·${userKey.slice(0, 8).toUpperCase()}`;
}

export function publicRankingEntry(row, meKey, legacyShape = false) {
  const correctAnswers = Number(row.verified_correct_answers || 0);
  const answeredQuestions = Number(row.answered_questions || 0);
  const activeChapters = Number(row.active_chapters || 0);
  const base = {
    position: Number(row.position || 0),
    displayName: publicRankingName(row.user_key),
    totalPoints: Number(row.total_points || 0),
    todayPoints: Number(row.daily_points || 0),
    rankName: rankForPoints(Number(row.total_points || 0)).name,
    isMe: Boolean(meKey && row.user_key === meKey),
  };
  if (legacyShape) {
    return {
      ...base,
      // Code 3 requires these names. They now describe server-verified authored
      // answers rather than the retired client-supplied progress snapshot.
      // V2 cannot prove whole-chapter completion, so never relabel question
      // counts as completed chapters for a legacy client.
      completedChapters: 0,
      activeChapters,
    };
  }
  return {
    ...base,
    verifiedCorrectAnswers: correctAnswers,
    verifiedAnsweredQuestions: answeredQuestions,
    activeChapters,
  };
}

function rankingTotalsSql() {
  return `
    SELECT user_key,
           SUM(points) AS total_points,
           SUM(correct) AS verified_correct_answers,
           COUNT(*) AS answered_questions,
           COUNT(DISTINCT chapter_id) AS active_chapters,
           MIN(received_at_ms) AS first_received_at
      FROM ${RANKING_TABLE}
     GROUP BY user_key
  `;
}

function rankingDailySql() {
  return `
    SELECT user_key,
           SUM(points) AS daily_points,
           SUM(correct) AS daily_correct_answers,
           COUNT(*) AS daily_answered_questions,
           MIN(received_at_ms) AS first_daily_received_at
      FROM ${RANKING_TABLE}
     WHERE beijing_day = ?
     GROUP BY user_key
  `;
}

async function loadRankings(env, dayKey, limit, meKey = '', legacyShape = false) {
  const dailySql = `
    WITH totals AS (${rankingTotalsSql()}),
         daily AS (${rankingDailySql()})
    SELECT totals.*, daily.daily_points,
           ROW_NUMBER() OVER (
             ORDER BY daily.daily_points DESC, totals.total_points DESC,
                      totals.verified_correct_answers DESC,
                      totals.first_received_at ASC, totals.user_key ASC
           ) AS position
      FROM totals
      JOIN daily ON daily.user_key = totals.user_key
     WHERE daily.daily_points > 0
  `;
  const totalSql = `
    WITH totals AS (${rankingTotalsSql()}),
         daily AS (${rankingDailySql()})
    SELECT totals.*, COALESCE(daily.daily_points, 0) AS daily_points,
           ROW_NUMBER() OVER (
             ORDER BY totals.total_points DESC,
                      totals.verified_correct_answers DESC,
                      totals.answered_questions ASC,
                      totals.first_received_at ASC, totals.user_key ASC
           ) AS position
      FROM totals
      LEFT JOIN daily ON daily.user_key = totals.user_key
     WHERE totals.total_points > 0
  `;
  const [dailyResult, totalResult] = await Promise.all([
    env.DB.prepare(`SELECT * FROM (${dailySql}) ORDER BY position ASC LIMIT ?`)
      .bind(dayKey, limit)
      .all(),
    env.DB.prepare(`SELECT * FROM (${totalSql}) ORDER BY position ASC LIMIT ?`)
      .bind(dayKey, limit)
      .all(),
  ]);
  const dailyRows = dailyResult.results || [];
  const totalRows = totalResult.results || [];
  let meDaily = dailyRows.find((row) => row.user_key === meKey);
  let meTotal = totalRows.find((row) => row.user_key === meKey);

  if (meKey && (!meDaily || !meTotal)) {
    const [dailyMine, totalMine] = await Promise.all([
      env.DB.prepare(`SELECT * FROM (${dailySql}) WHERE user_key = ?`)
        .bind(dayKey, meKey)
        .all(),
      env.DB.prepare(`SELECT * FROM (${totalSql}) WHERE user_key = ?`)
        .bind(dayKey, meKey)
        .all(),
    ]);
    meDaily ||= dailyMine.results?.[0] || null;
    meTotal ||= totalMine.results?.[0] || null;
  }
  return {
    daily: dailyRows.map((row) => publicRankingEntry(row, meKey, legacyShape)),
    total: totalRows.map((row) => publicRankingEntry(row, meKey, legacyShape)),
    meDaily: meDaily ? publicRankingEntry(meDaily, meKey, legacyShape) : null,
    meTotal: meTotal ? publicRankingEntry(meTotal, meKey, legacyShape) : null,
  };
}

const rankingTaskCache = new Map();

function bytesToHex(bytes) {
  return [...new Uint8Array(bytes)]
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('');
}

async function sha256Hex(bytes) {
  return bytesToHex(await crypto.subtle.digest('SHA-256', bytes));
}

async function loadRankingTaskIndex(env, contentVersion) {
  const release = contentReleaseForVersion(contentVersion);
  if (!release) throw new Error('content-version-not-accepted');
  if (!env.CONTENT_R2 || typeof env.CONTENT_R2.get !== 'function') {
    throw new Error('ranking-content-binding-missing');
  }
  const cached = rankingTaskCache.get(contentVersion);
  if (cached?.releaseSha256 === release.sha256) return cached.taskIndex;

  const object = await env.CONTENT_R2.get(release.key);
  if (!object || Number(object.size || 0) !== release.size) {
    throw new Error('ranking-content-unavailable');
  }
  const bytes = await object.arrayBuffer();
  if (bytes.byteLength !== release.size) throw new Error('ranking-content-size-mismatch');
  if (await sha256Hex(bytes) !== release.sha256) {
    throw new Error('ranking-content-hash-mismatch');
  }
  const taskIndex = requireCompleteAuthoredTaskIndex(
    JSON.parse(new TextDecoder().decode(bytes)),
  );
  rankingTaskCache.set(contentVersion, {
    releaseSha256: release.sha256,
    taskIndex,
  });
  return taskIndex;
}

async function readBoundedJson(request) {
  if (!/^application\/json(?:\s*;|$)/i.test(request.headers.get('Content-Type') || '')) {
    throw new Error('answer-event-body-content-type');
  }
  const declaredHeader = request.headers.get('Content-Length');
  if (
    declaredHeader !== null &&
    (!/^\d+$/.test(declaredHeader) || Number(declaredHeader) > MAX_EVENT_BODY_BYTES)
  ) {
    throw new Error('answer-event-body-too-large');
  }
  const reader = request.body?.getReader();
  if (!reader) throw new Error('answer-event-body-invalid');
  const chunks = [];
  let totalBytes = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      totalBytes += value.byteLength;
      if (totalBytes > MAX_EVENT_BODY_BYTES) {
        await reader.cancel('answer-event-body-too-large');
        throw new Error('answer-event-body-too-large');
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  if (totalBytes < 2) {
    throw new Error('answer-event-body-invalid');
  }
  const bytes = new Uint8Array(totalBytes);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    return JSON.parse(new TextDecoder().decode(bytes));
  } catch {
    throw new Error('answer-event-json-invalid');
  }
}

async function rankingReadiness(env) {
  requireRankingPepper(env.RANKING_PEPPER);
  if (!env.DB || typeof env.DB.prepare !== 'function') {
    throw new Error('ranking-d1-binding-missing');
  }
  if (!env.USER_CENTER || typeof env.USER_CENTER.fetch !== 'function') {
    throw new Error('ranking-user-center-binding-missing');
  }
  const anonymousSession = await userCenterJson(env, '/api/session', '');
  if (anonymousSession?.authenticated !== false) {
    throw new Error('ranking-user-center-session-unhealthy');
  }
  await env.DB.prepare(
    `SELECT event_id, user_key, canonical_task_id, chapter_id,
            content_version, task_semantic_digest, selected_option,
            correct, points, received_at_ms, beijing_day
       FROM ${RANKING_TABLE}
      LIMIT 1`,
  ).first();
  const manifest = await readAsset(env, '/manifest.json');
  if (!manifest) throw new Error('ranking-content-manifest-missing');
  const body = await manifest.json();
  const contentVersion = String(body.contentVersion || '');
  const taskIndex = await loadRankingTaskIndex(env, contentVersion);
  return { contentVersion, eligibleTaskCount: taskIndex.size };
}

function answerReceipt(row, status, submittedEventId = row.event_id) {
  return {
    eventId: submittedEventId,
    canonicalEventId: row.event_id,
    taskId: row.canonical_task_id,
    status,
    recorded: true,
    correct: Number(row.correct || 0) === 1,
    points: Number(row.points || 0),
    receivedAt: new Date(Number(row.received_at_ms)).toISOString(),
    beijingDay: row.beijing_day,
  };
}

function conflictAnswerReceipt(event) {
  return {
    eventId: event.eventId,
    canonicalEventId: null,
    taskId: event.taskId,
    status: 'conflict',
    recorded: false,
    error: 'answer-event-id-conflict',
  };
}

function sameAnswerEvent(row, event, taskSemanticDigest) {
  return row.canonical_task_id === event.taskId &&
    row.chapter_id === event.chapterId &&
    row.content_version === event.contentVersion &&
    row.task_semantic_digest === taskSemanticDigest &&
    row.selected_option === event.chosenOptionId;
}

async function recordVerifiedAnswer(env, auth, event, nowMs, taskSemanticDigest) {
  const sameId = await env.DB.prepare(
    `SELECT * FROM ${RANKING_TABLE} WHERE event_id = ?`,
  ).bind(event.eventId).first();
  if (sameId) {
    if (sameId.user_key !== auth.userKey ||
        !sameAnswerEvent(sameId, event, taskSemanticDigest)) {
      const error = new Error('answer-event-id-conflict');
      error.status = 409;
      throw error;
    }
    return answerReceipt(sameId, 'replayed', event.eventId);
  }

  const firstForTask = await env.DB.prepare(
    `SELECT * FROM ${RANKING_TABLE}
      WHERE user_key = ? AND canonical_task_id = ?`,
  ).bind(auth.userKey, event.taskId).first();
  if (firstForTask) {
    return answerReceipt(firstForTask, 'already-recorded', event.eventId);
  }

  const dayKey = beijingDayKey(new Date(nowMs));
  await env.DB.prepare(
    `INSERT OR IGNORE INTO ${RANKING_TABLE} (
       event_id, user_key, canonical_task_id, chapter_id, content_version,
       task_semantic_digest, selected_option, correct, points,
       received_at_ms, beijing_day
     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  ).bind(
    event.eventId,
    auth.userKey,
    event.taskId,
    event.chapterId,
    event.contentVersion,
    taskSemanticDigest,
    event.chosenOptionId,
    event.correct ? 1 : 0,
    event.points,
    nowMs,
    dayKey,
  ).run();

  const recorded = await env.DB.prepare(
    `SELECT * FROM ${RANKING_TABLE}
      WHERE user_key = ? AND canonical_task_id = ?`,
  ).bind(auth.userKey, event.taskId).first();
  if (!recorded) {
    const conflicting = await env.DB.prepare(
      `SELECT * FROM ${RANKING_TABLE} WHERE event_id = ?`,
    ).bind(event.eventId).first();
    if (conflicting) {
      const error = new Error('answer-event-id-conflict');
      error.status = 409;
      throw error;
    }
    throw new Error('answer-event-not-recorded');
  }
  if (recorded.event_id === event.eventId) {
    return answerReceipt(recorded, 'accepted', event.eventId);
  }
  return answerReceipt(recorded, 'already-recorded', event.eventId);
}

async function handleAnswerEvents(request, env) {
  const auth = await authenticateRanking(request, env);
  if (!auth || auth.invalid) {
    return json(
      { ok: false, error: 'login-required' },
      { status: 401, headers: { 'Cache-Control': 'no-store' } },
    );
  }
  const rawEvents = parseAnswerEventBatch(await readBoundedJson(request));
  const taskIndexes = new Map();
  const validated = [];
  for (const event of rawEvents) {
    let taskIndex = taskIndexes.get(event.contentVersion);
    if (!taskIndex) {
      taskIndex = await loadRankingTaskIndex(env, event.contentVersion);
      taskIndexes.set(event.contentVersion, taskIndex);
    }
    const answer = validateAuthoredAnswer(event, taskIndex, event.contentVersion);
    validated.push({
      answer,
      taskSemanticDigest: await sha256Hex(
        new TextEncoder().encode(answer.taskSemanticMaterial),
      ),
    });
  }

  const receipts = [];
  const receivedAtMs = Date.now();
  for (const item of validated) {
    try {
      receipts.push(
        await recordVerifiedAnswer(
          env,
          auth,
          item.answer,
          receivedAtMs,
          item.taskSemanticDigest,
        ),
      );
    } catch (error) {
      if (rankingEventErrorStatus(error) !== 409) throw error;
      receipts.push(conflictAnswerReceipt(item.answer));
    }
  }
  return json(
    {
      ok: true,
      schema: ANSWER_EVENT_SCHEMA,
      receipts,
    },
    { headers: { 'Cache-Control': 'no-store' } },
  );
}

async function handleRankings(request, env, legacyShape = false) {
  const url = new URL(request.url);
  const auth = await authenticateRanking(request, env);
  if (auth?.invalid) {
    return json(
      { ok: false, error: 'login-required' },
      { status: 401, headers: { 'Cache-Control': 'no-store' } },
    );
  }
  const nowMs = Date.now();
  const dayKey = beijingDayKey(new Date(nowMs));
  const board = await loadRankings(
    env,
    dayKey,
    boundedRankingLimit(url),
    auth?.userKey || '',
    legacyShape,
  );
  return json(
    {
      ok: true,
      schemaVersion: legacyShape ? 'weibian-rankings-v1' : RANKING_SCHEMA,
      period: { dayKey, timeZone: 'Asia/Shanghai' },
      maxPoints: MAX_AUTHORED_TASKS,
      rankingBasis: 'server-validated-first-authored-answer',
      syncAccepted: false,
      ...board,
      generatedAt: new Date(nowMs).toISOString(),
    },
    { headers: { 'Cache-Control': 'no-store' } },
  );
}

export function rankingEventErrorStatus(error) {
  const explicitStatus = Number(error?.status);
  if (explicitStatus === 409) return 409;
  const message = error instanceof Error ? error.message : '';
  return /^(?:answer-event-(?:body-(?:content-type|too-large|invalid)|json-invalid)|client-result-fields-forbidden|content-version-not-accepted|invalid-(?:answer-event(?:-envelope|-count)?|event-id|content-version|task-id|chapter-id|chosen-option)|task-(?:not-ranking-eligible|chapter-mismatch)|unknown-(?:answer-event-field|task-option))$/.test(message)
    ? 400
    : 503;
}

const APK_LATEST =
  'https://img.bdfz.net/apps/weibian-android/releases/v1.1.2/956810c9/weibian-1.1.2.apk';

function landingPage(counts) {
  const stat = (value, label) =>
    `<div class="s"><b>${value}</b><span>${label}</span></div>`;
  const stats = counts
    ? stat(counts.chapters, '章') +
      stat(counts.annotations, '条注释') +
      stat(counts.bank, '道精编题') +
      stat(counts.gaokaoQuestions, '道高考真题')
    : '';
  return `<!doctype html><html lang="zh-CN"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>韦编 · 论语译注</title>
<meta name="description" content="读完、读懂、记住《论语》全部 512 章的原生 Android 应用。">
<link rel="icon" href="https://img.bdfz.net/20250503004.webp" type="image/webp">
<link rel="apple-touch-icon" href="https://img.bdfz.net/20250503004.webp">
<style>
:root{--bg:#F7F3EA;--ink:#1C1A17;--soft:#4A453D;--red:#A8322A;--line:#D8D0C1;--card:#FFFDF7}
@media(prefers-color-scheme:dark){:root{--bg:#14161B;--ink:#ECE5D8;--soft:#A9A294;--red:#C8524A;--line:#393F49;--card:#1D2028}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);
 font:16px/1.75 -apple-system,BlinkMacSystemFont,"PingFang SC","Noto Sans CJK SC",sans-serif;
 letter-spacing:.02em}
.wrap{max-width:680px;margin:0 auto;padding:64px 24px 80px}
h1{font-size:34px;margin:0 0 6px;letter-spacing:.06em}
.sub{color:var(--soft);font-size:14px;margin:0 0 36px}
.q{border-left:3px solid var(--red);padding:2px 0 2px 16px;margin:0 0 36px;
 color:var(--soft);font-size:15px}
.stats{display:flex;flex-wrap:wrap;gap:10px;margin:0 0 32px}
.s{flex:1 1 120px;background:var(--card);border:1px solid var(--line);border-radius:12px;
 padding:16px 12px;text-align:center}
.s b{display:block;font-size:26px;color:var(--red);font-weight:600}
.s span{font-size:12px;color:var(--soft)}
h2{font-size:16px;margin:34px 0 10px;letter-spacing:.04em}
ul{margin:0;padding-left:20px;color:var(--soft);font-size:15px}
li{margin:6px 0}
a.dl{display:inline-block;background:var(--red);color:#FFF8F2;text-decoration:none;
 padding:13px 30px;border-radius:999px;font-size:15px;margin:28px 0 8px}
.note{font-size:12.5px;color:var(--soft);margin-top:26px;padding-top:18px;
 border-top:1px solid var(--line)}
code{font-size:12.5px;background:var(--card);border:1px solid var(--line);
 border-radius:5px;padding:1px 6px}
</style></head><body><div class="wrap">
<h1>韦编 · 论语译注</h1>
<p class="sub">Android 原生学习应用</p>
<p class="q">韦编三绝 —— 孔子读《易》，编简的皮绳断了三次。<br>
这个应用只为一件事：把《论语》全部读完、读懂、记住、并且能用。</p>
<div class="stats">${stats}</div>
<h2>它做什么</h2>
<ul>
<li>杨伯峻《论语译注》全本：原文分层展开译文与注释，正文注释标记可直接点开</li>
<li>三层练习：原文（识文·补字·连句）、注释（释词·概念辨析）、综合理解</li>
<li>北京卷《论语》历年真题，作答后由 AI 按阅卷标准批改</li>
<li>掌握度分读、练、复习三段计分 —— 一路划到底拿不到掌握</li>
<li>段位取《论语》本文：童蒙 → 志学 → 束脩 → 升堂 → 入室 → 博文 → 约礼 → 不惑 → 从心</li>
<li>完全离线可用；登录希悦账号后进度多端同步</li>
</ul>
<a class="dl" href="${APK_LATEST}">下载 Android 安装包</a>
<p class="note">
内容可独立于安装包更新：<code>/api/content/manifest</code> · <code>/api/content/bundle</code> ·
<code>/api/health</code><br>
《论语》原文属公有领域；译文与注释出自杨伯峻《论语译注》（中华书局），本项目已取得公开发布授权。
</p>
</div></body></html>`;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: CORS });
    }
    const allowedPost = request.method === 'POST' &&
      (url.pathname === '/api/rankings' || url.pathname === '/api/ranking-events');
    if (request.method !== 'GET' && request.method !== 'HEAD' && !allowedPost) {
      return json({ ok: false, error: 'method-not-allowed' }, { status: 405 });
    }

    switch (url.pathname) {
      case '/api/health': {
        const manifest = await readAsset(env, '/manifest.json');
        if (!manifest) {
          return json({ ok: false, error: 'content-missing' }, { status: 503 });
        }
        const body = await manifest.json();
        return json({
          ok: true,
          service: 'weibian-content',
          contentVersion: body.contentVersion,
          counts: body.counts,
        });
      }

      case '/api/rankings/health':
        try {
          const readiness = await rankingReadiness(env);
          return json(
            {
              ok: true,
              service: 'weibian-rankings',
              schemaVersion: RANKING_SCHEMA,
              rankingBasis: 'server-validated-first-authored-answer',
              ...readiness,
              d1: true,
              userCenter: Boolean(env.USER_CENTER),
              contentR2: true,
            },
            { headers: { 'Cache-Control': 'public, max-age=30' } },
          );
        } catch (error) {
          const requestId = crypto.randomUUID();
          console.error(JSON.stringify({
            event: 'weibian_rankings_health_failed',
            requestId,
            error: error instanceof Error ? error.message.slice(0, 120) : 'unknown',
          }));
          return json(
            { ok: false, error: 'rankings-unhealthy', requestId },
            { status: 503, headers: { 'Cache-Control': 'no-store' } },
          );
        }

      case '/api/rankings':
        try {
          return await handleRankings(request, env, true);
        } catch (error) {
          const requestId = crypto.randomUUID();
          console.error(JSON.stringify({
            event: 'weibian_rankings_request_failed',
            requestId,
            method: request.method,
            error: error instanceof Error ? error.message.slice(0, 120) : 'unknown',
          }));
          return json(
            { ok: false, error: 'rankings-unavailable', requestId },
            { status: 503, headers: { 'Cache-Control': 'no-store' } },
          );
        }

      case '/api/rankings/v2':
        try {
          return await handleRankings(request, env, false);
        } catch (error) {
          const requestId = crypto.randomUUID();
          console.error(JSON.stringify({
            event: 'weibian_rankings_v2_request_failed',
            requestId,
            method: request.method,
            error: error instanceof Error ? error.message.slice(0, 120) : 'unknown',
          }));
          return json(
            { ok: false, error: 'rankings-unavailable', requestId },
            { status: 503, headers: { 'Cache-Control': 'no-store' } },
          );
        }

      case '/api/ranking-events':
        if (request.method !== 'POST') {
          return json({ ok: false, error: 'method-not-allowed' }, { status: 405 });
        }
        try {
          return await handleAnswerEvents(request, env);
        } catch (error) {
          const requestId = crypto.randomUUID();
          const message = error instanceof Error ? error.message : 'unknown';
          const status = rankingEventErrorStatus(error);
          console.error(JSON.stringify({
            event: 'weibian_ranking_event_request_failed',
            requestId,
            status,
            error: message.slice(0, 120),
          }));
          return json(
            {
              ok: false,
              error: status === 409
                ? 'answer-event-id-conflict'
                : status === 400
                  ? 'answer-event-rejected'
                  : 'rankings-unavailable',
              requestId,
            },
            { status, headers: { 'Cache-Control': 'no-store' } },
          );
        }

      case '/api/content/manifest': {
        const manifest = await readAsset(env, '/manifest.json');
        if (!manifest) {
          return json({ ok: false, error: 'content-missing' }, { status: 503 });
        }
        const body = await manifest.json();
        // 只暴露 App 需要的字段，构建期的本机路径不外泄。
        return json(
          {
            schema: body.schema,
            schemaVersion: body.schemaVersion,
            contentId: body.contentId,
            contentVersion: body.contentVersion,
            sha256: body.sha256,
            size: body.size,
            builtAt: body.builtAt,
            counts: body.counts,
            bundleUrl: `${url.origin}/api/content/bundles/${body.contentVersion}.json`,
            deltas: Array.isArray(body.deltas)
              ? body.deltas.slice(0, 8).map((delta) => ({
                  fromSha256: String(delta.fromSha256 || '').toLowerCase(),
                  toSha256: String(delta.toSha256 || '').toLowerCase(),
                  sha256: String(delta.sha256 || '').toLowerCase(),
                  size: Number(delta.size || 0),
                  url: String(delta.url || ''),
                }))
              : [],
          },
          { headers: { 'Cache-Control': CACHE_MANIFEST } },
        );
      }

      case '/api/content/bundle': {
        const bundle = await readAsset(env, '/content.json');
        if (!bundle) {
          return json({ ok: false, error: 'content-missing' }, { status: 503 });
        }
        // v1.0.0 兼容路径是 mutable URL，绝不能标 immutable。
        return new Response(bundle.body, {
          headers: {
            'Content-Type': 'application/json; charset=utf-8',
            'Cache-Control': CACHE_MUTABLE,
            ...CORS,
          },
        });
      }

      case '/': {
        // 本项目没有网页版，weibian.bdfz.net 就是它对外唯一的门面。
        // 给一个能读、能下载的落地页，而不是把 JSON 甩给人看。
        const manifest = await readAsset(env, '/manifest.json');
        const counts = manifest ? (await manifest.json()).counts : null;
        return new Response(landingPage(counts), {
          headers: {
            'Content-Type': 'text/html; charset=utf-8',
            'Cache-Control': 'public, max-age=600',
          },
        });
      }

      default:
        if (
          url.pathname.startsWith('/api/content/bundles/') &&
          url.pathname.endsWith('.json')
        ) {
          const requestedVersion = url.pathname
            .slice('/api/content/bundles/'.length, -'.json'.length);
          const release = contentReleaseForVersion(requestedVersion);
          if (!release) {
            return json({ ok: false, error: 'content-version-not-found' }, { status: 404 });
          }
          const bundle = await env.CONTENT_R2.get(release.key);
          if (!bundle || bundle.size !== release.size) {
            return json({ ok: false, error: 'content-release-missing' }, { status: 503 });
          }
          return new Response(bundle.body, {
            headers: {
              'Content-Type': 'application/json; charset=utf-8',
              'Cache-Control': CACHE_IMMUTABLE,
              'Content-Length': String(release.size),
              ETag: `"${release.sha256}"`,
              ...CORS,
            },
          });
        }
        if (
          url.pathname.startsWith('/api/content/deltas/') &&
          url.pathname.endsWith('.json')
        ) {
          const filename = url.pathname.slice('/api/content/deltas/'.length);
          const key = deltaObjectKey(filename);
          if (!key) {
            return json({ ok: false, error: 'content-delta-invalid' }, { status: 400 });
          }
          const delta = await env.CONTENT_R2.get(key);
          if (!delta) {
            return json({ ok: false, error: 'content-delta-not-found' }, { status: 404 });
          }
          return new Response(delta.body, {
            headers: {
              'Content-Type': 'application/json; charset=utf-8',
              'Cache-Control': CACHE_IMMUTABLE,
              ETag: `"${filename.slice(0, -'.json'.length)}"`,
              ...CORS,
            },
          });
        }
        return json({ ok: false, error: 'not-found' }, { status: 404 });
    }
  },
};

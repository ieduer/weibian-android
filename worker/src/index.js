/**
 * 韦编内容接口 —— weibian.bdfz.net
 *
 * 负责两条项目自有边界：下发版本化内容包，以及从 User Center 已写入进度
 * 派生匿名学习榜。它不成为新的身份或原始学习记录权威。
 *
 * 端点：
 *   GET /api/content/manifest   内容清单（版本、sha256、体积、条目数）
 *   GET /api/content/bundles/<CONTENT_VERSION>.json  不可变完整内容包
 *   GET /api/content/bundle     旧客户端兼容入口（不可长期缓存）
 *   GET|POST /api/rankings      匿名榜读取 / 当前用户可信进度刷新
 *   GET /api/health             健康探针
 *
 * 刻意不做的事：
 *   · 不保存身份、Cookie 或原始进度 —— 那些仍由 my.bdfz.net 负责；
 *     排行榜只经 service binding 读取并保存 HMAC 假名聚合快照；
 *   · 不代理 AI —— 一律走 apis.bdfz.net 统一网关，本处不持有任何模型密钥。
 *
 * 当前 manifest 与旧客户端兼容 bundle 跟随 Worker Assets；已审核版本的
 * 不可变完整包由 R2 永久保存，并经 content-releases.js exact allowlist 暴露。
 * Worker 回滚恢复 manifest/allowlist，不能删除或覆盖已经发布的 R2 对象。
 */

import {
  beijingDayKey,
  MAX_CHAPTERS,
  MAX_POINTS,
  rankForPoints,
  requireRankingPepper,
  summarizeProgress,
} from './ranking.js';
import {
  contentReleaseForVersion,
  deltaObjectKey,
} from './content-releases.js';

const CACHE_IMMUTABLE = 'public, max-age=31536000, immutable';
const CACHE_MANIFEST = 'public, max-age=300';
const CACHE_MUTABLE = 'public, max-age=0, must-revalidate';
const USER_CENTER_ORIGIN = 'https://my.bdfz.net';
const RANKING_SCHEMA = 'weibian-rankings-v1';
const RANKING_TABLE = 'weibian_ranking_snapshots';
const MAX_RANKING_LIMIT = 30;
const MIN_RANKING_SYNC_INTERVAL_MS = 10_000;

/** App 与站点都可能来取内容，内容本身是公开资料，允许跨源读取。 */
const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS',
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

function cookieHeader(request) {
  return (request.headers.get('Cookie') || '').slice(0, 4096);
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
  if (!payload || payload.authenticated !== true || !payload.user) return '';
  return String(payload.user.slug || '').trim().slice(0, 96);
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
  const signature = await crypto.subtle.sign('HMAC', key, encoder.encode(slug));
  return [...new Uint8Array(signature)]
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('');
}

async function authenticateRanking(request, env) {
  const cookie = cookieHeader(request);
  if (!cookie.includes('bdfz_uc_session=')) return null;
  const pepper = requireRankingPepper(env.RANKING_PEPPER);
  const slug = sessionSlug(await userCenterJson(env, '/api/session', cookie));
  if (!slug) return null;
  return {
    cookie,
    userKey: await hmacUserKey(slug, pepper),
  };
}

function publicRankingName(userKey) {
  return `学子·${userKey.slice(0, 4).toUpperCase()}`;
}

function publicRankingEntry(row, meKey) {
  return {
    position: Number(row.position || 0),
    displayName: row.public_name,
    totalPoints: Number(row.total_points || 0),
    todayPoints: Number(row.daily_points || 0),
    completedChapters: Number(row.completed_chapters || 0),
    activeChapters: Number(row.active_chapters || 0),
    rankName: rankForPoints(Number(row.total_points || 0)).name,
    isMe: Boolean(meKey && row.user_key === meKey),
  };
}

async function loadRankings(env, dayKey, limit, meKey = '') {
  const dailySql = `
    SELECT *, ROW_NUMBER() OVER (
      ORDER BY daily_points DESC, total_points DESC,
               completed_chapters DESC, updated_at ASC
    ) AS position
      FROM ${RANKING_TABLE}
     WHERE day_key = ? AND daily_points > 0
  `;
  const totalSql = `
    SELECT *, ROW_NUMBER() OVER (
      ORDER BY total_points DESC, completed_chapters DESC,
               active_chapters DESC, updated_at ASC
    ) AS position
      FROM ${RANKING_TABLE}
     WHERE total_points > 0
  `;
  const [dailyResult, totalResult] = await Promise.all([
    env.DB.prepare(`${dailySql} LIMIT ?`).bind(dayKey, limit).all(),
    env.DB.prepare(`${totalSql} LIMIT ?`).bind(limit).all(),
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
        .bind(meKey)
        .all(),
    ]);
    meDaily ||= dailyMine.results?.[0] || null;
    meTotal ||= totalMine.results?.[0] || null;
  }
  return {
    daily: dailyRows.map((row) => publicRankingEntry(row, meKey)),
    total: totalRows.map((row) => publicRankingEntry(row, meKey)),
    meDaily: meDaily ? publicRankingEntry(meDaily, meKey) : null,
    meTotal: meTotal ? publicRankingEntry(meTotal, meKey) : null,
  };
}

async function syncCurrentRanking(env, auth, nowMs) {
  const existing = await env.DB.prepare(
    `SELECT synced_at_ms FROM ${RANKING_TABLE} WHERE user_key = ?`,
  ).bind(auth.userKey).first();
  if (existing && nowMs - Number(existing.synced_at_ms || 0) < MIN_RANKING_SYNC_INTERVAL_MS) {
    return false;
  }

  const progress = await userCenterJson(
    env,
    '/api/progress?site=weibian',
    auth.cookie,
  );
  const summary = summarizeProgress(progress);
  const dayKey = beijingDayKey(new Date(nowMs));
  await env.DB.prepare(
    `INSERT INTO ${RANKING_TABLE} (
       user_key, public_name, total_points, daily_points, completed_chapters,
       active_chapters, day_key, source_updated_at, synced_at_ms, updated_at
     ) VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
     ON CONFLICT(user_key) DO UPDATE SET
       public_name = excluded.public_name,
       daily_points = CASE
         WHEN ${RANKING_TABLE}.day_key = excluded.day_key
         THEN MIN(?, ${RANKING_TABLE}.daily_points
           + MAX(0, excluded.total_points - ${RANKING_TABLE}.total_points))
         ELSE MAX(0, excluded.total_points - ${RANKING_TABLE}.total_points)
       END,
       total_points = MAX(${RANKING_TABLE}.total_points, excluded.total_points),
       completed_chapters = MAX(
         ${RANKING_TABLE}.completed_chapters,
         excluded.completed_chapters
       ),
       active_chapters = MAX(
         ${RANKING_TABLE}.active_chapters,
         excluded.active_chapters
       ),
       day_key = excluded.day_key,
       source_updated_at = MAX(
         ${RANKING_TABLE}.source_updated_at,
         excluded.source_updated_at
       ),
       synced_at_ms = excluded.synced_at_ms,
       updated_at = CURRENT_TIMESTAMP`,
  ).bind(
    auth.userKey,
    publicRankingName(auth.userKey),
    summary.totalPoints,
    summary.completedChapters,
    summary.activeChapters,
    dayKey,
    summary.sourceUpdatedAt,
    nowMs,
    MAX_POINTS,
  ).run();
  return true;
}

async function handleRankings(request, env) {
  const url = new URL(request.url);
  const shouldSync = request.method === 'POST';
  const auth = await authenticateRanking(request, env);
  if (shouldSync && !auth) {
    return json(
      { ok: false, error: 'login-required' },
      { status: 401, headers: { 'Cache-Control': 'no-store' } },
    );
  }
  const nowMs = Date.now();
  const syncAccepted = auth && shouldSync
    ? await syncCurrentRanking(env, auth, nowMs)
    : false;
  const dayKey = beijingDayKey(new Date(nowMs));
  const board = await loadRankings(
    env,
    dayKey,
    boundedRankingLimit(url),
    auth?.userKey || '',
  );
  return json(
    {
      ok: true,
      schemaVersion: RANKING_SCHEMA,
      period: { dayKey, timeZone: 'Asia/Shanghai' },
      maxPoints: MAX_POINTS,
      maxChapters: MAX_CHAPTERS,
      syncAccepted,
      ...board,
      generatedAt: new Date(nowMs).toISOString(),
    },
    { headers: { 'Cache-Control': 'no-store' } },
  );
}

const APK_LATEST =
  'https://img.bdfz.net/apps/weibian-android/releases/v1.0.0/21fddd9a/weibian-1.0.0.apk';

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
    const rankingsPost = url.pathname === '/api/rankings' && request.method === 'POST';
    if (request.method !== 'GET' && request.method !== 'HEAD' && !rankingsPost) {
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
        return json(
          {
            ok: true,
            service: 'weibian-rankings',
            schemaVersion: RANKING_SCHEMA,
            d1: Boolean(env.DB),
            userCenter: Boolean(env.USER_CENTER),
          },
          { headers: { 'Cache-Control': 'public, max-age=30' } },
        );

      case '/api/rankings':
        try {
          return await handleRankings(request, env);
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

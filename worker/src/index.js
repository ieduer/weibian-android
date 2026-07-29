/**
 * 韦编内容接口 —— weibian.bdfz.net
 *
 * 只做一件事：把版本化的《论语》内容包下发给 App，让内容更新不必重发 APK。
 *
 * 端点：
 *   GET /api/content/manifest   内容清单（版本、sha256、体积、条目数）
 *   GET /api/content/bundle     完整内容包
 *   GET /api/health             健康探针
 *
 * 刻意不做的事：
 *   · 不碰身份与进度 —— 那是 my.bdfz.net（用户中心）的职责，
 *     本 Worker 复制一份就等于多一处会话与学习数据的攻击面；
 *   · 不代理 AI —— 一律走 apis.bdfz.net 统一网关，本处不持有任何模型密钥。
 *
 * 内容以 Worker 静态资源形式随部署一起发布（ASSETS 绑定），
 * 因此内容版本与部署一一对应，可用 wrangler rollback 整体回退。
 */

const CACHE_IMMUTABLE = 'public, max-age=31536000, immutable';
const CACHE_MANIFEST = 'public, max-age=300';

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

const APK_LATEST = 'https://img.bdfz.net/apps/weibian-android/latest.apk';

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
《论语》原文属公有领域；译文与注释出自杨伯峻《论语译注》（中华书局），仅作校内教学使用。
</p>
</div></body></html>`;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: CORS });
    }
    if (request.method !== 'GET' && request.method !== 'HEAD') {
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
            bundleUrl: `${url.origin}/api/content/bundle`,
          },
          { headers: { 'Cache-Control': CACHE_MANIFEST } },
        );
      }

      case '/api/content/bundle': {
        const bundle = await readAsset(env, '/content.json');
        if (!bundle) {
          return json({ ok: false, error: 'content-missing' }, { status: 503 });
        }
        // 内容包按版本内容寻址，命中后长期可缓存。
        return new Response(bundle.body, {
          headers: {
            'Content-Type': 'application/json; charset=utf-8',
            'Cache-Control': CACHE_IMMUTABLE,
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
        return json({ ok: false, error: 'not-found' }, { status: 404 });
    }
  },
};

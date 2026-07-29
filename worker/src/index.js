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

      case '/':
        return json({
          ok: true,
          service: 'weibian-content',
          app: '韦编 · 论语译注',
          endpoints: ['/api/health', '/api/content/manifest', '/api/content/bundle'],
        });

      default:
        return json({ ok: false, error: 'not-found' }, { status: 404 });
    }
  },
};

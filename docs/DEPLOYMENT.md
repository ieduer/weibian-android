## Current accepted release — 2026-10-06 UTC

Direct v1.2.0 / code6 and reviewed content 0b3170748035504b are accepted. See [the single release record](releases/v1.2.0.md) for exact source/artifacts, 0/1/5/100 and public readback, owner-waived physical acceptance, and rollback. This current record supersedes the retained preparation and earlier-release history below.

# 部署指南

## 一、内容接口 Worker

发布顺序：

1. 生成并验证 `content.json` / manifest；
2. 以 `<contentVersion>/<sha256>.json` 上传既有 R2 bucket
   `blog-images/apps/weibian-content/releases/`，先确认目标 404，禁止覆盖；
3. 公开读回 bytes/size/sha256；
4. 在 `worker/src/content-releases.js` 追加 exact version → R2 key；
5. 若有更小的 delta，上传 `apps/weibian-content/deltas/<from8>-<to8>.json`；
6. 更新 `content/public-content-lock.json`、`worker/public/manifest.json` 与
   App manifest；此时再运行 bootstrap，确认 clean clone 能重现同一 bytes；
7. Worker dry-run、deploy，最后移动内容 manifest 指针。

```bash
cd /Users/ylsuen/CF/lunyu-yizhu-android
/Users/ylsuen/.venv/bin/python content/build_content.py
# 先上传不可变对象并更新 lock / content-releases.js，再验证锁定对象：
node scripts/bootstrap_public_content.mjs
cd worker
npx wrangler deploy --dry-run
npx wrangler deploy
```

`bootstrap_public_content.mjs` 的 source 是已上传并经公开 readback 的 lock，
不是刚生成的 `content/dist/`。不要在更新 lock 之前运行它，否则会把工作树恢复
到上一个公开版本。

部署后自检：

```bash
curl -s https://weibian.bdfz.net/api/health | jq
curl -s https://weibian.bdfz.net/api/content/manifest | jq '{contentVersion,sha256,size,counts}'
# 校验下发内容与清单 sha256 一致
test "$(curl -s https://weibian.bdfz.net/api/content/bundles/<CONTENT_VERSION>.json | shasum -a 256 | cut -d' ' -f1)" \
   = "$(curl -s https://weibian.bdfz.net/api/content/manifest | jq -r .sha256)" && echo SHA-OK
```

Worker Assets 只保存当前 manifest/兼容 bundle；内容寻址 bundle 由 R2
永久保留，Worker 白名单映射旧版本。回退 Worker 会恢复旧 manifest，不能删除
或覆盖 R2 对象。

首次部署还需在 Cloudflare 为 `weibian.bdfz.net` 配置路由/自定义域。

### 上线前的注册事项（本机强制）

按 `runbooks/bdfz_project_matrix_and_interdependencies.md`，任何新公开站点必须
在同一次事务里登记到四个产品面 ＋ Pulse 监控面：

- [x] 用户中心 registry 与 feedback 已在 v242
  `ec273922-1ec4-442b-8c84-9a5e2f7fcdf5` 以 100% 流量上线，并完成 live
  registry readback、authenticated/idempotent feedback canary 与
  representative hub fan-out smoke；exact rollback 为 v240
  `96b9db71-a595-4ae3-a557-288b49bffd2f`
- [x] `bdfz-nav/sites.json`
- [x] canonical portal `https://i.rdfzer.com`（source:
  `/Users/ylsuen/CF/allinone-pages/public/index.html#portalGroups`）返回 200
  且含正确入口
- [x] Companion 明确记录 `not-applicable`；**不得**新增 Weibian WebView service
- [x] `pulse/src/sites.js`，并在 `/api/meta`、`/api/range` 实测到该 host

`allinone.bdfz.net` 与 `portal.bdfz.net` 当前返回 522，但它们不是 canonical
portal，也不替代 `i.rdfzer.com` 的发布验收。

**学生数据分级**：`student_owned`（写入学习进度）。因此上线前必须有一次
真实登录 + 进度写入 + 回读验证，仅加载脚本不算集成。

User Center 本次是从现行生产 bundle 做的一项外科式登记发布；当前生产 bundle
可精确回读与回滚，本地共享枢纽仓库也含该对象，但其 dirty/stale source 尚未
完成 clean Git source reconciliation。后续不得从未审工作树做普通 deploy。

---

## 二、APK 发布

### 签名

唯一 signing authority 是：

```text
/Users/ylsuen/.android/weibian-release.env
```

密钥与口令只从这个 600 权限的本机文件载入，**绝不入库、绝不打印**。正式
release 必须 fail closed：

```zsh
set -euo pipefail
cd /Users/ylsuen/CF/lunyu-yizhu-android
set -a
source /Users/ylsuen/.android/weibian-release.env
set +a

test -n "${WEIBIAN_ANDROID_KEYSTORE_PATH:-}"
test -n "${WEIBIAN_ANDROID_KEYSTORE_PASSWORD:-}"
test -n "${WEIBIAN_ANDROID_KEY_ALIAS:-}"
test -n "${WEIBIAN_ANDROID_KEY_PASSWORD:-}"

JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
  ./gradlew --no-daemon :app:clean :app:assembleDirectRelease

WEIBIAN_APK_PATH=app/build/outputs/apk/direct/release/app-direct-release.apk
test -f "$WEIBIAN_APK_PATH"
test ! -e app/build/outputs/apk/direct/release/app-direct-release-unsigned.apk
apksigner verify --verbose --print-certs "$WEIBIAN_APK_PATH"
```

Gradle 仍可为 CI／外部贡献者生成未签名候选，但任何 `*-unsigned.apk`、缺少
v1/v2 签名验证、signer continuity 不符或不是上述 authority 生成的输出都必须
拒收，不能进入 R2、GitHub Release、门户或实体安装验收。

Direct 与 Play 都必须解析为 canonical package `net.bdfz.weibian.direct`，
并由同一 app-signing lineage 签名；渠道只分离更新传输，不得形成两个安装项。

当前 Direct R2 release 是 v1.1.3 / versionCode 5：

- clean source/tag target：
  `abb140e23fa3eae5b532d03f86389e8d4992e2fd`
- Direct APK：2,819,955 bytes；SHA-256
  `9a1d67ef5ce0f43c9a8ed423c72c30cc8742f21123ebdca5399c5dd671ea2933`
- Play APK：2,819,954 bytes；SHA-256
  `fc3b5972c9aa214d41ae3df3227ddb80735456008436437416c1594e862af2d1`
- Play AAB：4,984,827 bytes；SHA-256
  `745faf3df4bf2ec6663d07d0d8a7076d55ca181884773a2cbf22a53da6b2ca23`
- signer certificate SHA-256：
  `a40f3956296d09ca2c6d8c3ec23f4f1d5470cb8ca6a5d4a69a9f19eb39941282`
- immutable Direct APK：
  `https://img.bdfz.net/apps/weibian-android/releases/v1.1.3/9a1d67ef/weibian-1.1.3.apk`
- Portal 固定最新版 APK：
  `https://img.bdfz.net/apps/weibian-android/latest.apk`

immutable APK／`release.json`、`latest.apk` 与 `latest.json` 已按 fail-closed
顺序上线，`latest.apk` 当前为同一 2,819,955 bytes，SHA-256
`9a1d67ef5ce0f43c9a8ed423c72c30cc8742f21123ebdca5399c5dd671ea2933`；
`latest.json` 最后移动且 `apkUrl` 仍指向上述 immutable APK。两个 mutable
URL 均只做 exact-URL edge purge。owner 明确豁免 v1.1.3 的实体装置与 App
acceptance；这些门未执行、不得称为通过。本轮没有触碰任何手机或资料。

以下是 v1.1.2 / code4 的历史实机证据：两台登记手机都经真实 App updater
从 code3 原位升级到 byte-identical exact code4。按 2026-07-30 新单机政策，
IN2020 是选定门机；它完成已记录的原位升级验收子集与同机平板效果并恢复
基线。2026-07-30 又以同机一次性 Android 次要使用者完成 data-safe
clean-profile 启动、核心内容、访客分区、手动自检与 scoped log 验收；本机
env canonical 账号的登录／同步／登出／重启读回也通过，Pulse progress
aggregate 由 7 行增至 8 行。LE2120 的部分证据保留为历史，owner 叫停后
不得再触碰，也不再是必要门。IN2020 随后在另一个 disposable user 中通过
active 单 byte 损坏后 previous-slot 物理恢复；测试 user、helper 与签名产物
全部清理。production landing 已提升并通过普通流量读回，当前 Direct
lifecycle 为 `production-supported`。

本次是 legacy closeout 的明确例外：owner 指定两台手机已安装的 byte-exact
code3 作为 code4 实体原位升级基线。它不把 code3 重分类为 public accepted
release；v1.1.3 的 owner waiver supersede 了当次必须实机升级的执行要求，
但没有把未执行的 code4→code5 路径重分类为已验证。
另需保留治理顺序偏差：v1.1.2 mutable pointer 与 GitHub Release 早于当时
全部实体门完成，landing/lifecycle 始终保持未提升，直到 2026-07-30 硬门
关闭后才 promotion。此顺序不得成为未来先移动 pointer 的先例。

**历史证据：**截至 2026-07-29，v1.1.1 / versionCode 3 的 final candidate
已从 clean checkpoint `e623e370a60bff33609e8bf5ad2748f559e20471`
构建：

- package：`net.bdfz.weibian.direct`
- size：2,738,032 bytes
- SHA-256：`de47da19562515049769c872f738975d8000091f9295f40e691d2928fe18da67`
- signer certificate SHA-256：
  `a40f3956296d09ca2c6d8c3ec23f4f1d5470cb8ca6a5d4a69a9f19eb39941282`
- `release.json`：625 bytes，SHA-256
  `9cfdb82006787800cc1612d8232257191815b7c3d06b33537695ccd946df4275`
- CI：[run 30466463323](https://github.com/ieduer/weibian-android/actions/runs/30466463323)

同一份 APK 与不可变 `release.json` 已公开并逐字节读回：

```text
https://img.bdfz.net/apps/weibian-android/releases/v1.1.1/de47da19/weibian-1.1.1.apk
https://img.bdfz.net/apps/weibian-android/releases/v1.1.1/de47da19/release.json
```

这只是历史 immutable staging；v1.1.2 已 supersede 它。不得把 v1.1.1
重新移动为 current／accepted，不得重签或覆盖上述历史对象。

发布前必须核对（`runbooks/bdfz_android_app_update_standard.md` §5）：

```zsh
set -e
WEIBIAN_APK_PATH=app/build/outputs/apk/direct/release/app-direct-release.apk
# 签名指纹须与上一个已接受版本一致
apksigner verify --print-certs "$WEIBIAN_APK_PATH" | grep SHA-256
# versionCode 必须严格递增
aapt2 dump badging "$WEIBIAN_APK_PATH" | head -1
```

**坏版本的修法是发一个更高 versionCode 的修复版**，不要靠"回退到更低版本号"。

### 上传（顺序不可颠倒，fail-closed）

内容寻址、不可覆盖：

```
apps/weibian-android/releases/v<SEMVER>/<HASH8>/weibian-<SEMVER>.apk
apps/weibian-android/releases/v<SEMVER>/<HASH8>/release.json
apps/weibian-android/latest.apk        （Portal 固定最新版下载；mutable）
apps/weibian-android/latest.json       （最后才写）
```

1. 先传**已签名的内容寻址 APK**
2. 再传不可变的 release.json
3. 从 exact signed artifact 覆写 `latest.apk`
4. 从不带 query 的 bare `latest.apk` 完整回读，确认 bytes、size、SHA-256
   与 immutable APK 完全一致
5. **最后**才更新 `latest.json` 指针
6. Portal 固定链接与真实下载旅程通过后才算完整发布

`latest.apk` 是用户便利入口，不是 manifest、审计或回滚 authority。
`latest.json.apkUrl` 必须始终保持上述内容寻址 immutable URL。Cloudflare
edge 可能继续缓存 bare alias；`Cache-Control: no-cache` 请求、HEAD 200 或
带 query 的 origin probe 都不能关闭发布门。若 bare URL 仍旧，只能使用
精确 URL purge 或等待刷新后重验；不得做全站 purge，也不得先移动
`latest.json`。

`latest.json` 必须符合 `bdfz-android-update-v1`。下列是占位模板；生成正式
JSON 时，`versionCode` 与 `size` 必须写成正整数，不能带引号：

```text
{
  "schema": "bdfz-android-update-v1",
  "appId": "net.bdfz.weibian.direct",
  "version": "<SEMVER>",
  "versionCode": <STRICTLY_INCREASING_VERSION_CODE>,
  "minAndroidApi": 23,
  "apkUrl": "https://img.bdfz.net/apps/weibian-android/releases/v<SEMVER>/<HASH8>/weibian-<SEMVER>.apk",
  "sha256": "<64 位小写十六进制>",
  "size": <EXACT_APK_BYTES>,
  "publishedAt": "<UTC_ISO8601>",
  "releaseNotes": ["<RELEASE_NOTE>"],
  "mandatory": false
}
```

客户端会拒绝：schema 或包名不符、versionCode 非递增、
`apkUrl` 不在 `https://img.bdfz.net/apps/weibian-android/releases/` 下、
sha256 格式非法、size ≤ 0、清单体积超限。这些校验都在
`update/AppUpdateManager.kt` 里，改契约要两边一起改。

当前公开 `latest.json` 已按 pointer-last 指向 v1.1.3 / versionCode 5，包名
为 `net.bdfz.weibian.direct`，并与 immutable Direct APK 的 bytes/hash/size
逐项读回一致。owner 豁免本版本实体 updater/App acceptance；未连接装置，
LE2120 未经重新授权不得触碰。code3→code4 仅属 v1.1.2 历史证据。

正式上传前，必须让仓库内 release guard 同时核对 APK、metadata、签名和
内容寻址 URL；不能靠人工目测 JSON：

```zsh
set -e
WEIBIAN_APK_PATH=app/build/outputs/apk/direct/release/app-direct-release.apk
WEIBIAN_RELEASE_JSON="<RELEASE_JSON_PATH>"
WEIBIAN_BUILD_TOOLS=/opt/homebrew/share/android-commandlinetools/build-tools/37.0.0

node scripts/verify_android_release.mjs \
  --apk "$WEIBIAN_APK_PATH" \
  --metadata "$WEIBIAN_RELEASE_JSON" \
  --aapt2 "$WEIBIAN_BUILD_TOOLS/aapt2" \
  --apksigner "$WEIBIAN_BUILD_TOOLS/apksigner" \
  --expected-signer a40f3956296d09ca2c6d8c3ec23f4f1d5470cb8ca6a5d4a69a9f19eb39941282 \
  --expected-app-id net.bdfz.weibian.direct \
  --expected-version 1.1.3 \
  --expected-version-code 5 \
  --previous-version-code 4
```

guard 任一项非零退出即停止；不得上传 APK、`release.json` 或移动 pointer。
CI 以 `node --test scripts/test/*.test.mjs` 锁定这条防线。

### GitHub Release

第二分发面，必须与 R2 **同一份字节**：

- 同样的 APK、同样的 sha256、同样的签名指纹
- 附 R2 不可变 URL
- 写明构建与安装方法
- 写明当前 lifecycle、production Worker、exact rollback 与任何仍开放门

v1.1.3 GitHub Release 已建立：

- tag/source：`v1.1.3` →
  `abb140e23fa3eae5b532d03f86389e8d4992e2fd`
- APK：2,819,955 bytes；SHA-256
  `9a1d67ef5ce0f43c9a8ed423c72c30cc8742f21123ebdca5399c5dd671ea2933`
- `release.json`：528 bytes；SHA-256
  `530ca9603627e529d9e70187cf1a14794012f759fa0e7679fbf847f62719f348`

landing functional source `88a7abbb7d47bd16951e0c73d011c6a391270fe2` 的
focused href contract、syntax 和 diff check 通过；production deployment
`52dc0a92-a906-4c67-a909-63da1992bed7` 已把
`8e4a53a2-a79f-4989-9f6e-287724553386` 提升到 100%，
`0b5f49e2-8ee3-4be3-98da-2d93ab0244ae` 保留为 exact rollback。普通流量的
exact landing link、health 与 verified caller identity 通过。physical App
acceptance 是 owner-waived/unverified，不能用 v1.1.2 的历史证据代替。

canonical portal 下载入口只使用 `https://i.rdfzer.com`。非 canonical 的
`allinone.bdfz.net`／`portal.bdfz.net` 522 不得被写成成功发布面。

canonical portal 的产品项固定指向 `https://weibian.bdfz.net`；App-owned
landing 再指向 exact immutable APK。每个后续 Direct release 必须验证 portal
产品链接仍到 landing，并验证 landing 的 immutable href，而不是把 portal 改成
Direct APK 按钮：

1. 公开回读 immutable APK 的 bytes/hash/size/signer；
2. 从同一 exact signed artifact 更新
   `https://img.bdfz.net/apps/weibian-android/latest.apk`，再从 bare URL
   完整下载并核对 bytes/hash/size；
3. 保持 `latest.json.apkUrl` 指向 immutable APK，最后才移动该 manifest；
4. 保留 `https://weibian.bdfz.net` 产品入口，并让
   `/Users/ylsuen/CF/allinone-pages/public/index.html` 中独立的
   `韦编安卓版` 永久指向固定 `latest.apk`；不得每版改回 immutable href；
5. `allinone-pages/scripts/verify.mjs` 必须锁定固定 alias。只有 Portal UI、
   verifier 或 service worker 本身改变时才需递增 cache version、跑
   immutable Preview 与重新部署 Production；
6. 每次 App release 都从 `https://i.rdfzer.com` 运行 live verifier，确认
   exact fixed href，并完成桌面／390×844 的真实下载旅程。

缺一项即不得把该版本称为完整发布或 `production-supported`。Portal 回滚使用
`allinone-pages/README.md` 记录的上一条 Production deployment。App release
回滚须先把 `latest.apk` 恢复为上一 accepted immutable bytes 并读回，再恢复
匹配的 `latest.json`；不得删除或覆盖 immutable APK。

---

## 三、回滚

| 故障 | 处理 |
|---|---|
| 内容包有错 | 回滚 Worker manifest；客户端可恢复 previous；保留 R2 对象作证据 |
| `latest.json`／`latest.apk` 指错 | 先从上一 accepted immutable APK 恢复 alias 并读回，再恢复匹配的 manifest |
| 门户页面坏了 | 恢复上一个 Pages/Worker 部署 |
| 坏 APK 已被安装 | 发**更高 versionCode** 的修复版；不要引导用户卸载重装 |
| 哈希/体积对不上 | 立即撤下指针与门户链接，**保留证据**，不要覆盖内容寻址对象 |
| 签名不一致 | 停止发布。绝不把"卸载后装未知签名版"训练成常规操作 |

所有生产变更与验证证据记入 `reports/agent_action_log.jsonl` 与运维总报告，
不写任何密钥、Cookie、会话 id 或学生内容。

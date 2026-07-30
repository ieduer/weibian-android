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

当前 Direct R2 release 是 v1.1.2 / versionCode 4：

- clean source：
  `e65dc572af19ed99cf520d52aa01de72508680a9`
- CI：[run 30516534134](https://github.com/ieduer/weibian-android/actions/runs/30516534134)
  （success）
- Direct APK：2,819,959 bytes；SHA-256
  `956810c903005680ba2e77a2c71964956cd2beac428e840862fc0a33724e15c3`
- Play APK：2,819,963 bytes；SHA-256
  `7bf92fcfc4fab561aee5f2e95a4ad80d67b9c7161778a667b8f7b33cc9427f7f`
- Play AAB：4,988,101 bytes；SHA-256
  `6a37903152ede8c5a9b4f9d547af99454cb75d501f19e3b96491969131b132a4`
- signer certificate SHA-256：
  `a40f3956296d09ca2c6d8c3ec23f4f1d5470cb8ca6a5d4a69a9f19eb39941282`
- immutable Direct APK：
  `https://img.bdfz.net/apps/weibian-android/releases/v1.1.2/956810c9/weibian-1.1.2.apk`

immutable APK／`release.json`、`latest.apk` 与 `latest.json` 已按 fail-closed
顺序上线，`latest.json` 最后移动。两台登记手机历史上都经真实 App updater
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
release；未来版本必须从当前 public accepted code4 在选定门机原位升级。
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
apps/weibian-android/latest.apk        （可选便捷别名）
apps/weibian-android/latest.json       （最后才写）
```

1. 先传**已签名的内容寻址 APK**
2. 再传不可变的 release.json
3. 可选别名
4. **最后**才更新 `latest.json` 指针
5. 门户下载链接等公开读**逐字节回读通过**之后再放出

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

当前公开 `latest.json` 已按 pointer-last 指向 v1.1.2 / versionCode 4，包名
为 `net.bdfz.weibian.direct`，并与 immutable Direct APK 的 bytes/hash/size
逐项读回一致。选定门机 IN2020 的真实 App updater code3 → code4 已通过；
LE2120 的历史附加证据不再构成必要门，未经重新授权也不得触碰。

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
  --expected-version 1.1.2 \
  --expected-version-code 4 \
  --previous-version-code 3
```

guard 任一项非零退出即停止；不得上传 APK、`release.json` 或移动 pointer。
CI 以 `node --test scripts/test/*.test.mjs` 锁定这条防线。

### GitHub Release

第二分发面，必须与 R2 **同一份字节**：

- 同样的 APK、同样的 sha256、同样的签名指纹
- 附 R2 不可变 URL
- 写明构建与安装方法
- 写明当前 lifecycle、production Worker、exact rollback 与任何仍开放门

v1.1.2 GitHub Release 已建立：

- tag/source：`v1.1.2` →
  `e65dc572af19ed99cf520d52aa01de72508680a9`
- APK：2,819,959 bytes；SHA-256
  `956810c903005680ba2e77a2c71964956cd2beac428e840862fc0a33724e15c3`
- `release.json`：741 bytes；SHA-256
  `0c8e317d1941c9b17f06f7d6b899a254b4f1f71da7ad78d05425cadf8330b67e`

landing source commit `4829b5bddf1bf3b18f01f8787f33082a96e5aaf7` 的 CI
run `30524470369` 已通过。IN2020 clean-profile、canonical 身份闭环和
physical active-corrupt → previous 已在 2026-07-30 关闭。production
deployment `3f5d9c74-593a-422b-8cbb-94ec31126b20` 已把
`1ce95b1a-e05c-4203-b082-324d6758aca5` 提升到 100%，
`e16da332-cbb5-46fd-82c8-ae7a6d4c69c0` 保留为 exact rollback。普通流量的
API、invalid-session 401、immutable APK、exact landing link、Pulse 和
桌面／390 px 真实浏览器均通过。

canonical portal 下载入口只更新 `https://i.rdfzer.com`。非 canonical 的
`allinone.bdfz.net`／`portal.bdfz.net` 522 不得被写成成功发布面。

---

## 三、回滚

| 故障 | 处理 |
|---|---|
| 内容包有错 | 回滚 Worker manifest；客户端可恢复 previous；保留 R2 对象作证据 |
| `latest.json` 指错 | 把指针改回上一个已知良好版本 |
| 门户页面坏了 | 恢复上一个 Pages/Worker 部署 |
| 坏 APK 已被安装 | 发**更高 versionCode** 的修复版；不要引导用户卸载重装 |
| 哈希/体积对不上 | 立即撤下指针与门户链接，**保留证据**，不要覆盖内容寻址对象 |
| 签名不一致 | 停止发布。绝不把"卸载后装未知签名版"训练成常规操作 |

所有生产变更与验证证据记入 `reports/agent_action_log.jsonl` 与运维总报告，
不写任何密钥、Cookie、会话 id 或学生内容。

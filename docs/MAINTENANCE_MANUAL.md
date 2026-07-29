# 韦编 · 论语译注 Android 运维手册

Status: `published-limited`
Public release: v1.0.0 / versionCode 1
Release candidate: v1.1.1 / versionCode 3（immutable staged，未成为 current／accepted）
Verified release checkpoint: `e623e370a60bff33609e8bf5ad2748f559e20471`
CI: GitHub Actions run `30466463323`, success
Last production-candidate verification: 2026-07-29
Source: `/Users/ylsuen/CF/lunyu-yizhu-android`

这是本项目开发、发布、值守、故障处理和交接的项目级事实入口。
“已公开发布”不等于 production-supported；本项目仍有实体平板、实体离线／
恢复、真实反馈、真实差异内容、final exact install 与 pointer/GitHub/landing
切换门未闭环。User Center registry 已 live，但 clean source reconciliation
仍是共享枢纽技术债。

## 0. 必读顺序与状态用词

每次工作依序阅读：

1. `/Users/ylsuen/CF/AGENTS.md`
2. `runbooks/bdfz_project_matrix_and_interdependencies.md`
3. `runbooks/bdfz_android_app_fleet_operations.md`
4. `runbooks/bdfz_native_app_development_standard.md`
5. `runbooks/bdfz_android_app_update_standard.md`
6. `runbooks/bdfz_backend_operations.md`
7. `runbooks/bdfz_unified_user_system.md`
8. 本仓库 `AGENTS.md`
9. `README.md`
10. `docs/ARCHITECTURE.md`
11. 本手册
12. `docs/DEPLOYMENT.md`
13. `docs/VERIFICATION_STANDARD.md`
14. `docs/SECURITY_REVIEW.md`

允许的当前表述：

- “v1.0.0 已公开发布”
- “v1.1.1 / code 3 的 final APK 与 release.json 已 immutable staged，但尚未
  移动公开更新指针、建立 GitHub Release 或成为 accepted release”
- “Worker 排行榜、内容不可变路由和差量契约已上线”
- “实体 OnePlus 手机上的 pre-final code 3 候选已通过‘我’页闪退回归、AI
  路径与 session persistence；不代表 final exact APK 已验收”
- “User Center v240 registry 已 100% live；clean Git source reconciliation
  仍开放”
- “canonical portal `i.rdfzer.com` 返回 200”
- “Companion disposition 是 `not-applicable`，没有 Weibian WebView”
- “当前 lifecycle 为 `published-limited`”

禁止的当前表述：

- “production-ready”
- “production-supported”
- “全部验收完成”
- “五面登记的 source authority 全部 clean/reconciled”
- “实体手机和平板都已验证”
- “差异内容版本已完成线上导入与回滚”
- “v1.1.1 已成为 current／accepted release”
- “应用内自更新端到端已验证”

## 1. App identity record

| 字段 | 当前值 |
|---|---|
| 产品名 | 韦编 · 论语译注 |
| fleetId / appKey | `weibian-android` |
| source path | `/Users/ylsuen/CF/lunyu-yizhu-android` |
| GitHub | `https://github.com/ieduer/weibian-android`（public） |
| default branch | `main` |
| v1.0.0 source/tag | `6512b57f0148e51b98452d166ce75c139ff68855` / `v1.0.0` |
| siteKey | `weibian` |
| base / Play package | `net.bdfz.weibian` |
| Direct package | `net.bdfz.weibian.direct` |
| Direct update | 客户端代码 enabled；公开 v1.0.0 `latest.json.appId` 仍错误，live contract 未通过 |
| Play update | disabled；商店流程未验收 |
| public host | `weibian.bdfz.net` |
| Worker | `weibian-content` |
| APK R2 prefix | `blog-images` / `apps/weibian-android/` |
| content R2 prefix | `blog-images` / `apps/weibian-content/` |
| update manifest | `https://img.bdfz.net/apps/weibian-android/latest.json` |
| immutable APK | `https://img.bdfz.net/apps/weibian-android/releases/v1.0.0/21fddd9a/weibian-1.0.0.apk` |
| APK SHA-256 | `21fddd9a82a6f486f5aa2e817716615fcd58b1d0d4f71ddff511eaaf8a1111ce` |
| APK size | 2,737,821 bytes |
| signer certificate SHA-256 | `a40f3956296d09ca2c6d8c3ec23f4f1d5470cb8ca6a5d4a69a9f19eb39941282` |
| candidate source | v1.1.1 / code 3 release checkpoint `e623e370…59e20471` |
| candidate APK | immutable staged；`…/v1.1.1/de47da19/weibian-1.1.1.apk`；SHA-256 `de47da19…8da67`；2,738,032 bytes；尚未成为 current／accepted |
| signing authority | `/Users/ylsuen/.android/weibian-release.env` |
| local data class | Room schema 1；学习记录不可再生 |
| central data class | `student_owned`；User Center progress/feedback |
| content schema | `lunyu-content-v1`, schemaVersion 1 |
| content version | `fc68413c7b70da0e` |
| lifecycle | `published-limited` |

这些键不得与 `lunyu`、`lunyu-battle`、`lunyu-battle-android`、
`recite-android` 或其他 App 混用。

### 当前发布证据

- Worker current version:
  `64f3c319-e3b8-429e-a49c-1e333fd2e15d`
- Previous Worker version:
  `437c965f-d420-43f0-8f08-c73a4b15a73b`
- GitHub Release:
  `https://github.com/ieduer/weibian-android/releases/tag/v1.0.0`
- v1.1.1 release checkpoint / CI:
  `e623e370a60bff33609e8bf5ad2748f559e20471` /
  `https://github.com/ieduer/weibian-android/actions/runs/30466463323`
  （success）
- v1.1.1 immutable staging:
  `https://img.bdfz.net/apps/weibian-android/releases/v1.1.1/de47da19/weibian-1.1.1.apk`
  与同目录 `release.json` 已公开逐字节验证；GitHub Release、landing 与
  `latest.json` 尚未切换。metadata 为 625 bytes，SHA-256
  `9cfdb82006787800cc1612d8232257191815b7c3d06b33537695ccd946df4275`
- User Center registry:
  v240 `96b9db71-a595-4ae3-a557-288b49bffd2f` 100% live，deployment
  `df473f1b-e57f-4e28-a06a-ef1c868e889f`；rollback v239
  `c3b71149-0c8a-460b-8613-ff789502a56a`
- Pulse:
  `weibian.bdfz.net`, source `worker_analytics`, status `tracked`
- canonical portal:
  `https://i.rdfzer.com`（200）
- Companion:
  `not-applicable`；无 Weibian WebView service

公开 `latest.json` 的 v1.0.0 `appId` 当前为 `net.bdfz.weibian`，与 Direct
package 不符。它是待修的公开指针，不是 self-update acceptance evidence。

版本 ID 是回滚锚，不代表内容、客户端和数据门全部通过。操作前重新读取
Wrangler/GitHub/R2；不得把本节旧值直接当作当前事实。

## 2. 产品和仓库边界

本项目独占：

- Android client、Room schema、App UI、构建、签名、release metadata；
- `weibian-content` Worker；
- `weibian.bdfz.net`；
- R2 `apps/weibian-android/` 与 `apps/weibian-content/`；
- GitHub `ieduer/weibian-android`。

本项目只读依赖：

| 路径/产品 | 用途 | 本项目能否写 |
|---|---|---|
| `CF/lunyu/data/dialogues.json` | 512 章正文、译文、注释来源 | 否 |
| `CF/lunyu-battle/src/data/` | 215 题、概念、人物 | 否 |
| `CF/gaokao/data/all.json` | 既有高考索引 | 否 |
| `CF/gks/data/papers/` | 真题材料 | 否 |
| `my.bdfz.net` | 身份、进度、反馈 | 只调用版本化 API |
| `apis.bdfz.net` | AI 讲解/批改 | 只调用共享契约 |
| `pulse.bdfz.net` | 运行监控和 aggregate | 只读验证；不从 APK 直写 |

禁止在本项目任务中顺手改上述语料或共享 hub。上游确需修复时另开 task，
明确 source owner、同源 Web/App disposition、fan-out、备份和回滚。

## 3. 架构和数据所有权

### 3.1 内容

- APK 内置 `assets/content.json`，保证离线首次使用。
- Worker manifest 提供 version/hash/size/counts/deltas；内容寻址 bundle/delta
  存在 R2，由 `content-releases.js` 白名单映射，旧版本跨 Worker 部署可回读。
- 内容与用户学习记录分离；内容包可再生，学习记录不可再生。
- 客户端以 `active/staged/previous` 三槽和原子重命名发布下载内容；启动只读取
  完整的 active，失败时保留 previous。
- `weibian-content-delta-v1` 支持同一内容 schema 内的前缀/后缀差量；
  base/hash/size 任一不符即安全回落完整 bundle。当前线上内容与 v1.0.0
  内容 hash 相同，因此 manifest 的 `deltas` 为空；真实差异版本导入/回滚仍是
  下一次内容变更的发布门。

### 3.2 学习记录

Room `weibian-learning.db` 保存：

- chapter progress；
- task attempts；
- daily stats；
- gaokao attempts；
- sync queue。

禁止 `fallbackToDestructiveMigration`。任何 schema 变更必须：

1. 导出新 schema；
2. 写显式 Migration；
3. 用真实上一版数据库跑升级；
4. 验证 force-stop/重启；
5. 验证登录、outbox 和内容版本；
6. 记录不可逆点与恢复路径。

### 3.3 身份与同步

当前客户端直接向 User Center `/api/login` 提交 username/password，一次登录
后只保存 Android Keystore 加密的 session Cookie。密码不落盘。

这条路径已由 `docs/IDENTITY_ADR.md` 明确接受为 v1.1 direct channel 的有限
一方适配器，并通过单次真实账号读写金丝雀。User Center 提供独立 App
handoff 后必须迁移；不得复用 Companion client 或退回 WebView 主界面。

进度读取使用 `GET /api/progress?site=weibian`，写入请求体使用
`siteKey=weibian`。两者字段不同是当前共享契约；写成 `site` 会 400。

outbox payload 携带 `schemaVersion`、稳定的 `clientMutationId`、platform、
App version、content version 和 source marker；同一队列项重试保持同一
mutation id。服务端仍以 `siteKey + itemKey` 的幂等 PUT 为权威写入契约。

### 3.4 段位与排行榜

“修为/段位/每日任务”保留本机即时体验；线上每日榜和总榜由
`weibian-content` 通过 User Center service binding 读取已写入进度后计算，
客户端不能提交总分。D1 只保存 HMAC 假名快照，不保存账号、姓名或 Cookie；
服务端 secret 缺失时 fail closed。榜单是学习激励，不是正式成绩。

### 3.5 内容权利

public Git 已通过 `.gitignore` 排除生成的译注语料，GitHub source tree 不含
`content.json`。但公开 APK 和无鉴权 Worker bundle 实际会分发译文/注释。

owner 已于 2026-07-29 明确确认本项目取得公开发布授权，项目级收据和来源、
分发面、撤回流程记录在 `docs/CONTENT_RIGHTS_RECEIPT.md`。新内容、来源或
授权范围变化仍须重新复核，不能把本次确认泛化到其他项目。

## 4. 配置与 secret 名称

### App build

唯一 signing authority：

```text
/Users/ylsuen/.android/weibian-release.env
```

release 必须只从该文件载入：

- `WEIBIAN_ANDROID_KEYSTORE_PATH`
- `WEIBIAN_ANDROID_KEYSTORE_PASSWORD`
- `WEIBIAN_ANDROID_KEY_ALIAS`
- `WEIBIAN_ANDROID_KEY_PASSWORD`

四项缺失时 Gradle 只可生成未签名候选，不能进入发布；不得 fallback 到其他
App 的通用 signing 文件、变量或 keystore。正式 release 必须使用 `set -e`、
`--no-daemon`、clean build，并以 `apksigner verify` 拒收任何 unsigned 输出；
完整命令以 `docs/DEPLOYMENT.md` 为准。正式发布同时必须核对既有 signer
certificate。

### Cloudflare

- `CLOUDFLARE_API_TOKEN`
- `CLOUDFLARE_ACCOUNT_ID`
- Worker secret `RANKING_PEPPER`

排行榜 D1 为 `weibian-rankings`，Worker 通过 `USER_CENTER` service binding
读取进度。不得在 APK、Worker source、报告、命令行参数或 Git 中记录 secret
值；排行榜 secret 缺失必须拒绝认证同步。

## 5. Dependency map

| 依赖边 | Contract probe | 变更影响 | Owner / rollback |
|---|---|---|---|
| App → User Center | `/api/version`、真实 session/progress/feedback canary | 登录、同步、反馈、80+ 站枢纽 | User Center owner；App 任务默认只读 |
| App → APIS | `Origin=https://weibian.bdfz.net`, `X-Project-Name=weibian` | AI 讲解/批改、~28 调用方 | APIS incident runbook；App 降级为离线 |
| App → content Worker | health/manifest/bundle hash | 内容热更新 | `weibian-content` version rollback |
| App → R2 | manifest/APK bytes/hash/size/signer | 安装和自更新 | immutable object + pointer；坏已装版发更高 code |
| Worker → R2 | `content-releases.js` exact key + public/Worker byte parity | 历史内容可回读 | immutable object 不删除；Worker mapping rollback |
| Worker → Assets | 当前 manifest/旧客户端兼容 bundle | 稳定指针与 v1.0 兼容 | Worker previous version |
| Pulse → Worker analytics | meta/range/live | 运行可见性 | Pulse owner；App 不直写 |
| build → source corpora | `build_content.py --check` | 内容、stable ID、alias | 上游 owner；另开 task |
| GitHub → R2 release | asset bytes/hash 相同 | 第二分发面 | Git tag/Release + immutable R2 |

本 App 是叶子，不得修改共享 hub contract。若必须改 User Center/APIS，
按矩阵手册现扫 fan-out，并把受支持 App 全部纳入回归。

## 6. 日常值守

### 每周

```bash
curl -sS https://weibian.bdfz.net/api/health | jq
curl -sS https://weibian.bdfz.net/api/content/manifest \
  | jq '{schema,schemaVersion,contentVersion,sha256,size,counts}'
curl -sS https://img.bdfz.net/apps/weibian-android/latest.json \
  | jq '{schema,appId,version,versionCode,apkUrl,sha256,size,publishedAt}'
curl -sS https://pulse.bdfz.net/api/meta \
  | jq '.. | objects | select((.host? // "") == "weibian.bdfz.net")'
curl -sS 'https://pulse.bdfz.net/api/range?range=24h' \
  | jq '.. | objects | select((.host? // "") == "weibian.bdfz.net")'
```

同时核对：

- GitHub Release 和 R2 APK digest；
- Worker errors/requests；
- User Center aggregate（只保留 aggregate）；
- feedback delivery；
- 版本 adoption/crash/ANR（若有受审核 aggregate）；
- canonical portal `i.rdfzer.com` 入口；
- 非 canonical `allinone.bdfz.net`／`portal.bdfz.net` 只作别名诊断，不作为
  本 App 发布门。

### 每月

- JDK/SDK/AGP/Kotlin/AndroidX 与 dependency/security；
- Room schema 和上一版数据库升级恢复；
- 权限、隐私说明和内容权利；
- signer backup 和公开 certificate continuity；
- R2/GitHub/manifest byte parity；
- User Center registry、nav、portal、frozen Companion disposition、Pulse。

Companion 对本项目的固定结论是 `not-applicable`；月检要确认没有后来新增的
Weibian WebView service，而不是要求把它加入 Companion。

### 每季

- 实体手机覆盖升级演练；
- 实体平板 adaptive/accessibility；
- Worker rollback sandbox/受控演练；
- 内容 staged/previous restore；
- lifecycle 是否仍为 `published-limited`。

## 7. 开发和构建

### 环境

- JDK 21: `/opt/homebrew/opt/openjdk@21`
- Android SDK: `/opt/homebrew/share/android-commandlinetools`
- Python: `/Users/ylsuen/.venv/bin/python`

### 无 mutation 的内容校验

```bash
cd /Users/ylsuen/CF/lunyu-yizhu-android
/Users/ylsuen/.venv/bin/python content/build_content.py --check
```

### 代码验证

```bash
cd /Users/ylsuen/CF/lunyu-yizhu-android
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew \
  --no-daemon \
  :app:testDirectDebugUnitTest \
  :app:lintDirectDebug \
  :app:assembleDirectDebug
```

Release build 只能在 identity release card 完整、项目专用 signing 输入就绪、
prior signer/version 已核对后执行。唯一 authority 是
`/Users/ylsuen/.android/weibian-release.env`；必须按 `docs/DEPLOYMENT.md`
使用 `set -euo pipefail`、`--no-daemon`、clean build，并拒收
`app-direct-release-unsigned.apk`。本次 final artifact 已锁定在 release
checkpoint `e623e370…59e20471`；后续状态文档不进入该二进制。若 App 代码、
资源或 build inputs 再变，必须提高 versionCode 后从新的 clean checkpoint
重建、重算 hash/size，并重新走完整验收。

### 内容生成

生成会写文件，必须先取得上游和生成物 ownership：

```bash
cd /Users/ylsuen/CF/lunyu-yizhu-android
/Users/ylsuen/.venv/bin/python content/build_content.py
cp content/dist/content.json app/src/main/assets/content.json
cp content/dist/manifest.json app/src/main/assets/content-manifest.json
cp content/dist/content.json worker/public/content.json
cp content/dist/manifest.json worker/public/manifest.json
```

生成物含授权语料但仍不入 Git。上传新的不可变 R2 bundle，逐字节读回后，
更新 `content/public-content-lock.json` 和 `worker/src/content-releases.js`；
clean clone 用 `node scripts/bootstrap_public_content.mjs` 恢复精确内容。

## 8. 发布

### 8.1 发布前 release card

必须记录：

- clean commit/tag；
- base/direct/play package；
- versionName/versionCode 与 prior accepted code；
- signer certificate current/prior；
- APK/AAB hash/size；
- Room schema/migration；
- content version/schema/digest/rights；
- Worker current/previous version；
- User Center/Pulse/feedback/phone/tablet/upgrade evidence；
- R2 immutable URL；
- rollback 和未过门。

`e623e370a60bff33609e8bf5ad2748f559e20471` 与 CI run `30466463323`
是 v1.1.1 final artifact 的 release checkpoint；该 exact APK 已 immutable
staged。其后的状态文档不进入 v1.1.1 artifact，也不触发重签；任何二进制修复
都必须使用同 signer 和更高 versionCode。

### 8.2 Worker/内容

先上传不可变 R2 bundle/delta 并逐字节读回，再追加 release mapping、更新
manifest、dry-run、记录 current version，最后部署 Worker 指针：

```bash
cd /Users/ylsuen/CF/lunyu-yizhu-android/worker
npx wrangler deploy --dry-run
```

正式 deploy 只在 task 明确授权、内容 rights/hash、cache contract、App
差异版本导入和回滚均通过后执行。不得覆盖 R2 内容寻址对象，也不得把
`/api/content/bundle` 兼容 URL 标成 immutable。

当前另有只供实体差异内容验收的 canary Worker version
`8207191d-81aa-4367-b403-7c6bdd32d27e`，流量为 0%；ordinary production
仍由 `64f3c319-e3b8-429e-a49c-1e333fd2e15d` 承载。B bundle
`4a97b261…e3703`（871,334 bytes）与 A→B delta `83d407be…8b1f`
（259 bytes）已 immutable 上传并公开读回，但尚未在实体 App 导入，不能写成
内容更新验收通过。

### 8.3 APK

顺序固定：

1. signed content-addressed APK；
2. immutable release metadata；
3. 可选便利别名；
4. 公开 bytes/hash/size/signer readback；
5. `latest.json` 最后；
6. GitHub Release 与公开入口；
7. 安装后重验。

v1.0.0 是第一个已接受 release，没有更低 production APK 可作为已安装客户端
回滚。坏版已安装后只能用同 signer、更高 versionCode 修复。

Landing page 必须链接到当前 accepted release 的内容寻址 APK，不能链接
`latest.apk` 便利别名。v1.1.1 pointer 未移动前仍指向 immutable v1.0.0；
但当前公开 v1.0.0 `latest.json.appId` 错误，不能据此声称 Direct 自更新通过。

## 9. 监控和验收

### Backend

通过条件：

- health `ok=true`；
- manifest schema/version/counts 正确；
- bundle bytes/hash/size 与 manifest 一致；
- Pulse meta/range/live 收录 Worker，无异常错误；
- User Center 和 APIS 安全 probe 正常。

### App

不能仅用 emulator。production-supported 至少需要：

- 指定实体手机 serial；
- 指定实体平板 serial；
- clean install；
- v1.0.0 → 更高 code 覆盖升级；
- cold/force-stop/foreground/back/rotation/multi-window/offline/recovery；
- canonical 真实账号登录、同步、登出/撤销、重启读回；
- feedback canary → API → aggregate D1 → Telegram receipt；
- AI 讲解和高考批改；
- 差异内容版本下载、hash、导入、重启、rollback；
- scoped logcat 无 fatal/ANR；
- direct/Play 渠道行为分离。

截至当前，实体 OnePlus 上的 pre-final code 3 候选已通过“我”页闪退回归、
AI 路径与 session persistence。final exact
`de47da19…8da67` install、physical feedback、offline/recovery、
rotation/multi-window、差异内容导入／回滚、实体平板和最终
release/self-update 仍是 open gate。User Center registry v240 已 live，不再是
open canary gate。

## 10. 故障排查

### 登录

| 现象 | 原因与处理 |
|---|---|
| “登录成功，但服务器没有返回会话” | User Center 未下发 `bdfz_uc_session`；查 `/api/version` 和 incident runbook |
| “注册入口已关闭” | 当前预期：服务端 `registration-disabled`；不要在 App 新建用户表 |
| 反复登录失败 | 停止重试，避免锁定；核对账号规范化和 User Center 状态 |
| 重装后需重登 | 预期：Keystore session 不跨设备恢复 |
| 登录后进度未回 | 手动同步；核对 GET `?site=weibian` 与 PUT `body.siteKey=weibian` |
| 换账号看到前一账号状态 | P0；停止同步并检查本机账号隔离，不得清库掩盖 |

### 同步

| 现象 | 处理 |
|---|---|
| “待同步 N 条”不下降 | 保留队列；查网络/session/API，不手工删队 |
| 两设备不一致 | 双方各手动同步；核对 monotonic merge，不接受回退 |
| 游客登录后担心覆盖 | 当前策略合并较优记录；需继续验证账号归属隔离 |
| 重复写入 | 检查 itemKey PUT；补齐 `clientMutationId` 前不得宣称完整事件幂等 |

### APK 更新

| 现象 | 处理 |
|---|---|
| “更新检查暂不可用” | 非阻断；查 manifest HTTP/schema/size |
| 检查不到新版 | 手动强制；核对 versionCode 严格增加 |
| “更新地址不在允许范围内” | URL 必须在 `apps/weibian-android/` |
| “清单与当前应用不匹配” | Direct 必须精确为 `net.bdfz.weibian.direct`；先查实际 APK package 和 manifest |
| Play 看不到 direct 入口 | 预期；Play 不得 sideload |
| hash/size 不符 | P1；停止 pointer/入口，保全 artifact，不覆盖 immutable object |
| signer 不符 | P0；停止发布，不引导卸载接受陌生 signer |

### 内容更新

| 现象 | 处理 |
|---|---|
| manifest 变了但内容不生效 | 检查 stable bundle 的 immutable CDN 缓存和 hash mismatch |
| 下载后回落内置包 | 核对 bundle hash、schema、解析错误和 active meta |
| 某章注释不可点 | 查 `unresolvedMarkers`；雍也 6.7、6.26 是已知上游缺口 |
| 注释序号错 | 改上游另开 task，重跑管线；不要手改生成物 |
| 真题未回挂 | 检查 LCS 阈值和 source；无材料微写作可不挂 |
| 需要回滚 | 启动会清除不完整 staged；可回退 previous；不得删除学习记录 |

### Portal/登记

| 现象 | 处理 |
|---|---|
| `i.rdfzer.com` 没有 Weibian | 这是 canonical portal 故障；检查当前 Pages deployment、源条目与 rollback |
| `allinone.bdfz.net`／`portal.bdfz.net` 522 | 非 canonical 别名的既存状态；不得把它误报为本 App portal gate |
| User Center 看不到 Weibian | v240 registry 已 live；检查 deployment `df473f1b…` 与 `/api/sites`，必要时按已记录 v239 rollback；不得从未审工作树 deploy |
| Companion 出现 Weibian WebView entry | 违反已接受的 `not-applicable` disposition；移除该 entry，不新增 WebView service |
| Pulse 没数据 | 先看 meta 是否登记，再看 range source/coverage；request 不等于用户 |

## 11. 回滚

### Worker/内容

当前 previous version:

```text
437c965f-d420-43f0-8f08-c73a4b15a73b
```

操作前重新读取 `wrangler versions list/deployments list`，确认它仍是目标
previous version。不要凭文档直接回滚。

```bash
wrangler rollback <REVIEWED_PREVIOUS_VERSION_ID> \
  --name weibian-content \
  --yes
```

回滚后复验 health、manifest、bundle hash、App import 和 Pulse。

物理内容验收 canary `8207191d-81aa-4367-b403-7c6bdd32d27e` 当前为 0%
流量；ordinary production rollback anchor 是
`64f3c319-e3b8-429e-a49c-1e333fd2e15d`。若 canary 被临时提升，结束后须将
该 ordinary version 恢复至 100% 并读回 deployment。

### APK

- `latest.json` 指错：先保存当前 pointer bytes/hash，再回指已验证 artifact。
- 当前没有 code 0/更早 production release；v1.0.0 已安装坏版只能发同 signer、
  更高 code 修复。
- immutable APK 不覆盖、不删除。
- GitHub Release 是审计面，不以删除 Release 代替客户端修复。

### Room/User Center

- migration 只向前；
- 不清 Room/D1/User Center 作为回滚；
- backend 新字段优先 dormant；
- 先证明修复版可读现有 schema，再发更高 code。

## 12. 已知故障模式与经验

1. User Center progress 读用 `site` query，写用 `siteKey` body；两边不能凭名称
   猜成相同。
2. 朱砂色接近 error，不能表示“答对”；正确状态用青瓷。
3. `fillMaxSize().widthIn()` 不能实现大屏限宽；要由父容器约束。
4. AI 网关可能返回 Markdown；纯文本 UI 需要稳定 prompt/renderer contract。
5. 内容包通过 R8 不能只看 APK 大小；必须读取包内 asset 并比 bytes/hash。
6. mutable bundle URL 配 `immutable` 会造成 manifest 新、bundle 旧。
7. public Git 不含语料不等于 APK/Worker 没有再分发内容；权利审查要看全部分发面。
8. GitHub Release、R2、Worker、Pulse 各自成功仍不等于实体设备验收。
9. Profile 的单一 `LazyColumn` 里，收藏与笔记不能只用相同 `chapterId` 当 key；
   同章同时出现会触发 duplicate-key crash。现在使用 section-namespaced key，
   并由单测和实体 OnePlus pre-final code 3 候选整页滚动回归锁定。
10. `latest.json` HTTP 200 不是 self-update 验收；`appId` 必须精确匹配
    `net.bdfz.weibian.direct`，且要由实体 Direct App 完成读取和升级验证。

## 13. 技术债和阻塞项

| 优先级 | 项目 | 关闭证据 |
|---|---|---|
| P1 | 无实体平板正式验收 | 指定实体平板 serial 的 adaptive/accessibility 完整核查记录 |
| P1 | feedback 未做真实回执 | physical App → API → aggregate D1 → Telegram |
| P1 | 实体 offline/recovery 矩阵未闭环 | 断网启动、读练、恢复联网、force-stop/rotation/multi-window 与 scoped logcat |
| P1 | 差量契约尚无真实差异版本导入/回滚 | physical App 的 patch/full fallback、重启与 previous rollback |
| P1 | final exact APK 未做实体安装验收 | 在指定实体手机覆盖安装 `de47da19…8da67`，确认数据/session/profile，并完成 scoped log |
| P1 | v1.1.1 release/self-update 未闭环 | immutable R2 已完成；仍须 GitHub/landing byte parity、正确 Direct `latest.json` pointer-last、实体读回 |
| Shared-hub debt（非 App lifecycle 硬门） | User Center clean source 未对账 | 将已 live 的单一 registry object 归并到经审 clean Git source；在此之前禁止普通 hub deploy，并验证 production bundle parity |
| P2 | 注册 UI 预留但服务关闭 | User Center 正式开放或明确永久不支持 |

## 14. 后续变更

任何新功能、共享 hub 改动、新 release、内容分发扩展或 production 状态提升
都必须另开 task。优先顺序：

1. final exact APK 实体覆盖安装与 physical feedback 回执；
2. 实体 offline/recovery、rotation/multi-window、真实差异内容导入/回滚和实体平板；
3. 按 fail-closed 顺序发布正确 Direct `latest.json`，完成
   R2/GitHub/`i.rdfzer.com` 字节一致性和实体自更新；
4. 另开共享枢纽维护 task 对账 User Center clean source，保持 v240
   production bundle 可回滚；这项技术债不用于阻断已验证的 Weibian App
   lifecycle；
5. Play store。

## 15. Closeout

任务结束必须记录：

- changed files/production resources；
- tests、设备 serial、live endpoints；
- commit/tag、Worker version、APK/hash/signer；
- rollback；
- dirty tree；
- unresolved。

更新 `reports/agent_action_log.jsonl` 的 `change`、`verify`、`closeout`。
canonical report 被另一个 active owner 持有时不得抢写；在 handoff 明确指出
待其收口后补 association index。

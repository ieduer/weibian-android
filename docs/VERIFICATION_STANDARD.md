# 韦编 · 核查标准（八点）

依 `runbooks/bdfz_project_matrix_and_interdependencies.md` §8 —— 本机强制。
**跑完这份标准才算"改完"**，"构建成功"不算。

当前 Direct lifecycle：`production-supported`。身份、渠道和状态先读
`runbooks/bdfz_android_app_fleet_operations.md` 与本仓库
`docs/MAINTENANCE_MANUAL.md`；任何后续 release 必须重新执行本文件，不能
沿用本次收据自动提升。

---

## 1. 事实来源（Source of Truth）

| 资产 | 位置 |
|---|---|
| App 源码 | `/Users/ylsuen/CF/lunyu-yizhu-android`（本仓库） |
| 内容管线 | `content/build_content.py` |
| 公开内容锁 | `content/public-content-lock.json` |
| 内容语料上游 | `CF/lunyu/data/dialogues.json`、`CF/lunyu-battle/src/data/`、`CF/gaokao/data/all.json`、`CF/gks/data/papers/` |
| 内容接口 | Worker `weibian-content` → R2 `blog-images/apps/weibian-content/` → `weibian.bdfz.net` |
| 身份与进度 | `my.bdfz.net`（`bdfz-user-center`，D1 `bdfz-user-center-db`）—— **本项目只是调用方** |
| AI | `apis.bdfz.net` —— **本项目只是调用方，无密钥** |
| APK 发布 | R2 `blog-images` → `img.bdfz.net/apps/weibian-android/` |
| canonical Portal 下载项 | `/Users/ylsuen/CF/allinone-pages/public/index.html` + `scripts/verify.mjs#APK_DOWNLOADS` → 固定 `https://img.bdfz.net/apps/weibian-android/latest.apk` |
| signing authority | `/Users/ylsuen/.android/weibian-release.env`（600，不入库、不打印） |

**上游语料是只读的。** 本项目从不写 `CF/lunyu`、`CF/lunyu-battle`、`CF/gaokao`、`CF/gks`。

## 2. 健康探针

```bash
curl -s https://weibian.bdfz.net/api/health | jq
# 期望 {"ok":true,"service":"weibian-content","contentVersion":"...","counts":{...}}

curl -s https://img.bdfz.net/apps/weibian-android/latest.json \
  | jq '{schema,appId,version,versionCode,apkUrl,sha256,size,publishedAt}'
# HTTP 200 只是连通性；Direct acceptance 还要求 appId 精确为
# net.bdfz.weibian.direct、versionCode 严格递增、artifact bytes/hash/size/signer
# 全部一致，并在实体 Direct App 完成升级。

WEIBIAN_RELEASE_URL="$(curl -fsS https://img.bdfz.net/apps/weibian-android/latest.json | jq -er .apkUrl)"
WEIBIAN_APK_TMP="$(mktemp -d)"
curl -fsSL "$WEIBIAN_RELEASE_URL" -o "$WEIBIAN_APK_TMP/immutable.apk"
curl -fsSL -H 'Cache-Control: no-cache' \
  https://img.bdfz.net/apps/weibian-android/latest.apk \
  -o "$WEIBIAN_APK_TMP/latest.apk"
cmp -s "$WEIBIAN_APK_TMP/immutable.apk" "$WEIBIAN_APK_TMP/latest.apk"
test "$(stat -f '%z' "$WEIBIAN_APK_TMP/latest.apk")" \
  = "$(curl -fsS https://img.bdfz.net/apps/weibian-android/latest.json | jq -er .size)"
test "$(sha256sum "$WEIBIAN_APK_TMP/latest.apk" | awk '{print $1}')" \
  = "$(curl -fsS https://img.bdfz.net/apps/weibian-android/latest.json | jq -er .sha256)"

curl -s https://my.bdfz.net/api/version | jq -r .version     # 依赖枢纽存活
curl -s https://pulse.bdfz.net/api/meta \
  | jq '.. | objects | select((.host? // "") == "weibian.bdfz.net")'
curl -s 'https://pulse.bdfz.net/api/range?range=24h' \
  | jq '.. | objects | select((.host? // "") == "weibian.bdfz.net")'
curl -s -X POST https://apis.bdfz.net/ -H 'Content-Type: application/json' \
  -H 'Origin: https://weibian.bdfz.net' -H 'X-Project-Name: weibian' \
  -d '{"prompt":"用一句话解释「学而时习之」"}' | jq -r '.answer // .data.answer'
```

Alias parity 必须使用 Portal 实际链接的不带 query URL并下载完整 body。
cache-busted origin probe、HEAD 200 或按钮存在都不能替代 bytes/size/SHA-256
一致；检查后删除 `$WEIBIAN_APK_TMP`。bare alias 仍旧时 release fail closed。

## 3. 契约核查

```bash
# 内容包与清单 sha256 必须一致
test "$(curl -s https://weibian.bdfz.net/api/content/bundles/fc68413c7b70da0e.json | shasum -a 256 | cut -d' ' -f1)" \
   = "$(curl -s https://weibian.bdfz.net/api/content/manifest | jq -r .sha256)" && echo SHA-OK

# 从锁定的公开不可变对象重建 clean-clone 资产
node scripts/bootstrap_public_content.mjs

# 内容管线自校验（章数/篇章数/答案/诊断/引用）
/Users/ylsuen/.venv/bin/python content/build_content.py --check

# 内容、领域逻辑、lint 与 debug 构建
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew \
  --no-daemon \
  :app:testDirectDebugUnitTest \
  :app:testPlayDebugUnitTest \
  :app:lintDirectDebug \
  :app:lintPlayDebug \
  :app:assembleDirectDebug \
  :app:assemblePlayDebug
```

更新清单契约 `bdfz-android-update-v1` 的客户端校验在 `update/AppUpdateManager.kt`；
进度契约字段（`itemKey=chapter-<id>`、`state`、`progressPercent`、`meta.*`）
在 `data/LearningRepository.kt#enqueue` 与 `network/ApiClient.kt#pullProgress`，
**两边必须同时改**。

每个 Direct release 还必须让 `https://i.rdfzer.com` 的独立
`韦编安卓版` 永久指向固定
`https://img.bdfz.net/apps/weibian-android/latest.apk`，并在移动
`latest.json` 前证明该 bare alias 与 manifest 指向的 immutable APK 完全
一致。Portal 产品入口仍指向 `https://weibian.bdfz.net`，不得用 APK URL
取代。同步流程、真实浏览器／live verifier 门与回滚见
`docs/DEPLOYMENT.md`；任一项缺失即不得维持 `production-supported` 结论。

## 4. 部署命令与禁止事项

部署见 [DEPLOYMENT.md](DEPLOYMENT.md)。**禁止**：

- 覆盖任何已公开读过的内容寻址 APK 对象；
- 覆盖任何已公开读过的内容 bundle/delta R2 对象；
- 用更低 versionCode 做"回滚"；
- 在 App 内内置任何模型密钥或绕过 `apis.bdfz.net`；
- 在本项目里新建用户表、密码、注册或找回流程；
- 修改上游语料仓库（`CF/lunyu`、`CF/lunyu-battle`、`CF/gaokao`、`CF/gks`）；
- Room 用 `fallbackToDestructiveMigration`；
- 在 release 身份未核对时使用其他 App 的 package、R2 prefix、签名变量或 signer；
- 从 `/Users/ylsuen/.android/weibian-release.env` 以外的 signing authority
  生成正式包，或接受任何 unsigned release 输出；
- release build 不使用 `set -e`／`--no-daemon`，或把任何非 release
  checkpoint 的 interim digest 当作 final；
- 把可变 `/api/content/bundle` 作为一年 immutable 对象发布新内容；
- 把签名口令、Cookie、会话 id 写进日志、报告或提交。

## 5. 依赖回归

本项目是**叶子**，不是枢纽 —— 它消费用户中心、AI 网关、img 图床和
Pulse。叶子自身 UI/内容改动不需要全舰队扇出，但必须验证 Android 客户端、
落地页、content Worker、R2/GitHub 和登记面的本项目闭环。

但下列改动会碰枢纽契约，必须按矩阵手册做扇出：

| 改动 | 需回归 |
|---|---|
| 变更进度写入格式 | 用户中心 `/api/progress` 的其他调用方 |
| 变更 AI 网关调用头 | `apis.bdfz.net` 其余 ~28 个调用方 |
| 新增/改名/下线本站点 | 四个产品登记面 ＋ Pulse（见 DEPLOYMENT.md） |

## 6. 备份与恢复

- **不可再生**：用户的学习记录（Room `weibian-learning.db`）、签名密钥。
- **可再生**：内容包（`build_content.py` 从上游语料重建）、APK。
- 学习记录随系统备份（`backup_rules.xml` 含 db，**排除**加密会话 —— Keystore 密钥
  不可跨设备还原，还原了也解不开，不如让用户重登）。
- 已登录用户的进度另有服务端副本，换机可从 `/api/progress?site=weibian` 拉回。
- 签名密钥丢失 = 无法再发兼容更新，必须离线另存备份。
- 客户端保留 active/staged/previous；R2 保留每个内容寻址 bundle，Worker
  `content-releases.js` 只把已审核版本映射到公开路由。删除旧对象不是回滚。

## 7. 回滚

见 [DEPLOYMENT.md](DEPLOYMENT.md) 第三节与
[MAINTENANCE_MANUAL.md](MAINTENANCE_MANUAL.md) §11。锚点：
Worker reviewed previous version；`latest.json` 指针；Git tag/R2 immutable
artifact；更高 versionCode 的同 signer 修复版。v1.0.0 是首个 accepted
release，没有可供已安装客户端降级的更早 production APK。

## 8. Verification receipts（2026-07-28 onward）

**已通过：**

| 项 | 证据 |
|---|---|
| 内容完整性 | 512 章 / 20 篇章数吻合杨伯峻本 / 1045 条注释 / 215 题 / 7 组 23 道真题 |
| 单元测试 | 26 项全绿（含直接解析随包 `content.json`） |
| 构建 | `assembleDirectDebug` 成功，APK 20 MB |
| 冷启动 | Android 15 模拟器（arm64），首帧 2.24 s，**crash 缓冲为空** |
| 阅读 | 学而 1.1 原文渲染、注释标记为红色上标、中文折行正常 |
| 点注释 | 点 ⑷ → 正确弹出杨伯峻注「说：音读和意义跟"悦"字相同…」 |
| 出题 | 识文题生成正确，干扰项取自别章真实原文 |
| 即时反馈 | 对错配色区分（青瓷绿/朱砂红）、解析与回章入口可用 |
| 持久化 | 重启后修为 15、连续 1 天、错题 1、学习 2 分钟 均正确保留 |
| 过程性评分边界 | 排行榜与个人页均明示本机修为、段位、服务端核验学习榜不计入用户中心六维 A—F 分数或 A+ 门槛 |
| 学程图 | 20 篇章数正确，可展开章格与掌握度点阵 |
| 真题 | 7 组列出；6 组含原文材料者回挂正确（11.26 / 11.22 / 4.5 / 9.6·7.28·7.20 / 17.8），2018 微写作无引文所以保持空映射 |
| 深色模式 | 靛底暖字，对比正常 |
| 离线（emulator-only） | 未登录、无网络时全书可读可练；不能替代当时仍开放的实体 offline/recovery 矩阵 |

**2026-07-29 历史追加验证（当时全部通过）：**

| 项 | 证据 |
|---|---|
| 内容接口上线 | `weibian.bdfz.net/api/health` 返回 512/1045/215/23；落地页可读 |
| 内容包完整性（线上） | manifest.sha256 == bundle 实测 sha256（`fc68413c…fa75`） |
| **历史真实账号契约验证（非 final exact 实机门）** | canonical 授权账号单次登录成功（未重试）；以 App 同形载荷 PUT 一条非计分 `chapter-1` 金丝雀 → `/api/progress?site=weibian` 回读 → 清理；不保留账号、Cookie 或原始学生内容。此记录只证明身份／进度契约，不替代 final exact APK 的实体登录与持久化验收 |
| 进度契约修正 | 发现读写字段名不一致（读 `?site=`、写 `body.siteKey`），客户端原先写 `site` 会 400，已修并实测通过 |
| **平板／大屏（emulator-only）** | 2560×1600 @320dpi 模拟器：原为拉满全宽，已改 `BoxWithConstraints` 定宽 760dp 居中；手机模拟器 1080×2400 布局无回归。只能补充，不能替代选定实体手机上的可逆 expanded-layout 验收 |
| **AI 讲解（App 内）** | 章句页「问先生」实测返回简体白话分点讲解；发现网关回 Markdown 而 App 纯文本渲染，已加提示词约束＋客户端剥离（8 项测试锁定） |
| **签名构建能力** | final v1.1.1 APK 已从 `e623e370…59e20471` 用专用 authority 构建；2,738,032 bytes，SHA-256 `de47da19…8da67`，signer continuity 与包内 `assets/content.json` 已验证 |
| **发布顺序执行** | immutable APK 与 `release.json` 已先上传并公开逐字节读回；`latest.json`、GitHub Release 与 landing 尚未切换，故 v1.1.1 尚未成为 current／accepted release |
| **自检更新身份修正** | 公开 v1.0.0 `latest.json.appId` 已于 2026-07-30 从错误 base 修正为 `.direct`，公开 bytes SHA-256 `d50ead93…e1db3` 且逐 byte 读回；这只修复当前清单身份，不代替 code4 选定门机覆盖验收 |
| 登记 live 分层 | nav、Pulse、canonical portal `i.rdfzer.com` 已 live；Companion 为 `not-applicable` 且无 WebView；User Center v242 registry/feedback 已 100% live 并完成 authenticated/idempotent canary 与 representative fan-out smoke；v240 是 exact rollback，clean source reconciliation 仍开放 |

**2026-07-29 正式接管只读复核（历史快照）：**

| 项 | 证据 |
|---|---|
| lifecycle | 线上确有 v1.0.0，但 production-ready 门未全过；定为 `published-limited` |
| Worker | current version `1e5d96ff-fe6d-45e4-a425-8deb4d75a3a7`；health 512/1045/215/23 |
| 内容 readback | bundle 871,333 bytes；SHA-256 `fc68413c…ffa75` 与 manifest 一致 |
| APK readback | immutable R2 与 `latest.apk` 都是 2,737,821 bytes；SHA-256 `21fddd9a…111ce` |
| 实际安装身份 | Direct package `net.bdfz.weibian.direct`、versionCode 1、min/target 23/37；v1/v2 签名通过 |
| signer | certificate SHA-256 `a40f3956…41282` |
| GitHub | public Release v1.0.0 asset digest 与 R2 一致；public source 不含生成语料 |
| release checkpoint | `e623e370a60bff33609e8bf5ad2748f559e20471`；release guard tests、Android unit/lint/build 与 Worker tests 通过 |
| CI | GitHub Actions run `30466463323`, success |
| v1.1.1 immutable staging | APK `…/v1.1.1/de47da19/weibian-1.1.1.apk` 与同目录 `release.json` 已公开精确读回；APK 2,738,032 bytes，SHA-256 `de47da19…8da67`，signer `a40f3956…41282` |
| User Center registry（历史快照） | 当时 v240 `96b9db71…ffd2f` 100% live，deployment `df473f1b…889f`；13 probes、代表性依赖与 10m zero-error evidence 通过。当前已由下表的 v242 supersede |
| 落地页 | desktop 与 390×844 响应式布局通过；390 CSS viewport 无水平溢出 |
| Pulse | meta/range 收录 `weibian.bdfz.net`，source `worker_analytics`，read-only 观察为 0 errors |
| portal | canonical `i.rdfzer.com` 返回 200 且有正确入口；`allinone.bdfz.net`、`portal.bdfz.net` 是非 canonical 522 别名 |

**2026-07-29 historical production-candidate 追加证据：**

| 项 | 证据 |
|---|---|
| 实体手机 code 3 baseline | 登记的 LE2120 与 IN2020 均安装 byte-identical final code 3 `de47da19…8da67`；package/signer 一致，既有 first-install identity 与 App 资料保留，作为 code4 原位更新起点 |
| Profile 闪退修复 | 同章同时出现在收藏和笔记时，旧版以重复 `chapterId` 作为同一 `LazyColumn` key 而 crash；现已改为 section-namespaced key，并在实体 OnePlus 的 pre-final code 3 候选完成“我”页整页反复滚动，scoped crash buffer 无本 App fatal |
| AI（实体 App） | OnePlus pre-final code 3 候选的 AI 讲解和非敏感高考批改路径均返回可用结果；不代表 final exact APK 或其余实体门已通过 |
| 原子内容发布 | `ContentReleaseFilesTest` 覆盖 staged → active、previous 保留和中断恢复 |
| 差量契约 | B bundle `4a97b261…e3703`（871,334 bytes）与 A→B delta `83d407be…8b1f`（259 bytes）已 immutable 上传并公开精确读回；LE2120 与 IN2020 都通过真实 delta、故意拒绝 delta 后整包回落、重启 readback 与 stable A 恢复；canary 已撤流 |
| Worker | 当时 current `e16da332-cbb5-46fd-82c8-ae7a6d4c69c0` 100%；当时 immediate rollback `32f8dd97-9d50-49e1-a0cf-9f1277dd0c92`；ranking v2 health、匿名／失效会话、content/landing/R2 路径均 live readback 通过 |
| 安全基线 | 明确禁止 cleartext；JSON 2 MiB 上限；同步重试有界；排行榜 secret 缺失 fail closed；gitleaks 无命中 |
| 内容权利 | owner 明确授权；`CONTENT_RIGHTS_RECEIPT.md` 入档 |
| 身份/签名契约 | `IDENTITY_ADR.md` 接受有限 native adapter；release 仅接受 `WEIBIAN_ANDROID_*`；Direct manifest 精确匹配 `.direct` |
| signing authority | 唯一 authority 为 `/Users/ylsuen/.android/weibian-release.env`；final `de47da19…8da67` 以 `set -e`、`--no-daemon` 构建，unsigned 输出被拒收 |
| portal / Companion | canonical portal `i.rdfzer.com` 200；两个 bdfz alias 522 为非 canonical；Companion disposition `not-applicable`、无 Weibian WebView |

**2026-07-30 v1.1.2 / code 4 当前发布证据：**

| 项 | 证据 |
|---|---|
| source / CI | clean source `e65dc572af19ed99cf520d52aa01de72508680a9`；GitHub Actions run `30516534134` success |
| Direct APK | v1.1.2 / code4；2,819,959 bytes；SHA-256 `956810c903005680ba2e77a2c71964956cd2beac428e840862fc0a33724e15c3`；signer `a40f3956…41282` |
| Play artifacts | APK 2,819,963 bytes／SHA-256 `7bf92fcfc4fab561aee5f2e95a4ad80d67b9c7161778a667b8f7b33cc9427f7f`；AAB 4,988,101 bytes／SHA-256 `6a37903152ede8c5a9b4f9d547af99454cb75d501f19e3b96491969131b132a4`；与 Direct 共用 canonical package/signing lineage |
| R2 release | `…/v1.1.2/956810c9/weibian-1.1.2.apk` 与同目录 `release.json` 已 immutable 上线并公开精确读回；固定 `latest.apk` 为同一 2,819,959 bytes、SHA-256 `956810c9…e15c3`，`latest.json` 最后移动且 `apkUrl` 保持 immutable |
| GitHub Release | [v1.1.2](https://github.com/ieduer/weibian-android/releases/tag/v1.1.2) target `e65dc572…`；APK 2,819,959 bytes / SHA-256 `956810c9…e15c3`；`release.json` 741 bytes / SHA-256 `0c8e317d…0b67e` |
| IN2020 code4 | 当前选定门机；依 owner 对本次 legacy closeout 的明确指示，以已安装 byte-exact code3 作为实体验收基线，经真实 App updater 原位升级；code3 不因此成为 public accepted release，未来 release 必须从当前 public accepted code4 升级；资料／Session、榜单、反馈、offline/recovery、Back、rotation/multi-window、AI／注释、current-update、single-package 均通过；sw753dp／200% font expanded-layout 通过且设备基线恢复 |
| LE2120 code4 | 历史补充证据；真实 App updater 原位升级到同一 APK，登录、榜单与反馈通过后 owner 叫停；临时 Wi-Fi proxy 是否恢复为 None 未确认，未经重新授权不得触碰，但它不再是本 release 的必要门 |
| User Center | v242 `ec273922-1ec4-442b-8c84-9a5e2f7fcdf5` current；v240 `96b9db71-a595-4ae3-a557-288b49bffd2f` exact rollback |
| Worker / landing | deployment `3f5d9c74…` 由 v1.1.2 `1ce95b1a…@100%` 承载；ordinary exact API、invalid-session 401、content/ranking、Pulse、immutable APK 与桌面／390 px 真实浏览器通过；rollback `e16da332…` |
| canonical Portal | 固定 href contract 为 `https://img.bdfz.net/apps/weibian-android/latest.apk`，当前 bare alias 与 v1.1.2 immutable APK 同为 2,819,959 bytes／SHA-256 `956810c9…e15c3`；Preview `eb969e54-be14-45ad-b13d-03a183170ec9` 与 Production `ebb74beb-743b-4f8f-b32e-be345fd4a8cc`（source `b4afbe32862da697b288f4b7182b98e9565b34ab`）均通过四 App alias／manifest／immutable 完整下载校验；Production 桌面与 390×844 浏览器中四个下载项均为 44px、无横向溢出且 console 0 error／warning。立即回滚为 `986d02af-468c-41b2-a0a9-71fe99d183fa`；完整移除 Weibian 下载项的历史回滚为 `7be72ef0-c83a-46db-9f2f-b5187c51a1bf` |
| IN2020 clean-profile | disposable Android user 映射同一 exact code4 package；0/512 clean state、游客分区、章句、译文／注释、手动 current-update 与 scoped log 通过；切回 owner 后临时 user 已删除，owner App 未卸载／未清资料 |
| IN2020 canonical 身份 | 使用本机 env 凭据完成登录／同步／冷启读回／登出／未登录冷启／重新登录读回；非计分进度 canary 令 Pulse aggregate rows 7→8、users 保持 1；临时 helper package 与 credential-bearing 临时文件已移除；切回 owner 后 cold launch 204 ms，账号已连接、已读 3 章、无待同步，只有 user 0 与唯一 Weibian package；size 1080×2376、density 450、font 1.0、rotation 1/0、global/Wi-Fi proxy None、stay-awake 15、timeout 2147483647 均读回，近 15 分钟 scoped fatal/ANR 为 0；不冒称 server-side token revoke |
| IN2020 active-corrupt → previous | disposable user 10 的 active/previous 初始同为 871,333 bytes、SHA `fc68413c…ffa75`；只改 active offset 0（123→122）后坏 SHA `f89eb7b1…35554`；exact code4 353 ms cold launch 恢复原 SHA，previous/staged/failed 均消失，512 章 UI 与 scoped fatal/ANR 通过；user/helper/device files/本机签名测试产物已全部删除，owner App 未卸载／未清资料 |
| lifecycle | Direct `production-supported`；Play channel 仍 disabled，未来启用须另跑 Play gate |

**Release closeout matrix：**

- [x] **IN2020 同机平板效果门**：以 reversible forced layout 完成
      sw753dp / 200% font 的主流程、独立榜单、Profile、更新、注释与 AI
      验证；size/density/rotation/proxy/font/keep-awake 已恢复。
- [x] **IN2020 应用内反馈**：final exact code4 physical App 的回馈链通过；
      LE2120 的历史回执仅作附加证据，不再要求复验。
- [x] **两台实体手机差异内容下载／整包回落／重启**：LE2120 与 IN2020
      均通过真实 delta；故意错误 digest 均被拒绝并自动 full fallback；
      active bytes/SHA 精确，force-stop/cold restart 后持久化；测试后 stable
      内容测试后稳定 A 已恢复；当前综合 Worker
      `1ce95b1a-e05c-4203-b082-324d6758aca5` 为 100%。
- [x] **内容 previous/corrupt-active rollback**：取得风险明示批准后，只在
      IN2020 disposable user 10 安装同 signer 临时 instrumentation；验证
      active/previous 同 SHA 后仅改 active byte 0。exact code4 cold launch
      拒绝坏 active、把 previous 恢复为原 SHA，清除 previous/staged/failed，
      UI 与 scoped logs 通过；随后删除 user、helper、设备暂存与本机全部
      正式签名测试产物，owner App 未卸载／未清资料。
- [x] **选定门机 offline/recovery 矩阵**：IN2020 的 final code4
      offline/recovery、rotation/multi-window 与 scoped log 已通过。LE2120
      由 owner 叫停且不再是必要门；其 proxy 风险保留为历史设备事项。
- [x] **data-safe clean-profile 与 canonical 身份闭环**：IN2020 使用
      disposable Android user/profile 映射同一 exact code4 package，完成
      clean state、游客分区、核心内容、手动自检与 scoped log；切回 owner
      后删除临时 user，未卸载／未清 owner App。使用本机 env canonical 账号
      完成登录、同步、冷启读回、登出、未登录冷启及重新登录读回；Pulse
      aggregate progress rows 7→8、users 保持 1。临时 helper package 与
      credential-bearing 临时文件均已移除。当前 User Center `/api/logout`
      无 token denylist，不得冒称 server-side revoke。
- [x] **选定实体手机的 final exact APK**：IN2020 已安装 exact
      `956810c9…e15c3` code4 并通过 updater、资料/Session、榜单、反馈、
      offline/recovery、rotation/multi-window、current-update 与 single-package。
      LE2120 已安装同一 APK 并通过部分附加证据，但不再是必要门；模拟器不得
      替代选定实体手机。
- [ ] **共享枢纽技术债（非本 App lifecycle 硬门）**：User Center v242
      registry/feedback 已 live，v240 是 exact rollback；dirty/stale source
      必须在未来任何普通 User Center deploy 前归并到经审 clean Git source。
- [x] **v1.1.2 / code 4 release closeout**：R2 immutable、固定
      `latest.apk` bare URL 与 immutable APK bytes/size/SHA-256 parity、
      `latest.json` immutable `apkUrl` pointer-last、GitHub Release、IN2020 单机／同机平板效果、
      clean-profile、canonical 身份和 active-corrupt → previous 均通过；
      landing deployment `3f5d9c74…` 已将 `1ce95b1a…` 提升至 100%，普通
      API/Pulse/desktop/390 px 浏览器读回通过。旧 v1.0.0 / v1.1.1 仅保留为
      历史证据，`e16da332…` 是当前 Worker rollback。

以下不是 open gate：

- canonical portal `i.rdfzer.com` 已 200；两个 bdfz 522 域只是非 canonical alias。
- Companion 已明确 `not-applicable`，没有 Weibian WebView service。

> 模拟器证据一律标注为 **emulator-only**。
> 认证与同步必须用真实账号端到端回读后才能声称可用；界面上有登录框不算同步证据。

**最后验证人／日期**：Codex production-supported closeout 验证，2026-07-30。
IN2020 已覆盖 final exact code4 updater、资料/Session、榜单、反馈、
offline/recovery、rotation/multi-window、AI／注释、current-update、
single-package 与补充 expanded-layout，并恢复设备基线。LE2120 只有 updater、
登录、榜单、反馈的部分历史证据且 proxy 恢复未确认；它不再是必要门。
IN2020 的 disposable-user clean-profile 与本机 env canonical 身份闭环也已
通过；physical active-corrupt → previous 亦已在独立 disposable user 通过。
临时 user/helper/credential-bearing files、设备暂存和正式签名测试产物均已
清理。landing 已提升并经普通 production 浏览器与 API/Pulse 读回，当前
Direct lifecycle 为 `production-supported`。浏览器、R2、GitHub、Wrangler、
Pulse 和 CI 仍不能替代未来 release 的实体 Android 重验。

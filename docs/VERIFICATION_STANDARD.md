# 韦编 · 核查标准（八点）

依 `runbooks/bdfz_project_matrix_and_interdependencies.md` §8 —— 本机强制。
**跑完这份标准才算"改完"**，"构建成功"不算。

当前 lifecycle：`published-limited`。身份、渠道和状态先读
`runbooks/bdfz_android_app_fleet_operations.md` 与本仓库
`docs/MAINTENANCE_MANUAL.md`；本文件通过前不得提升为
`production-supported`。

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

curl -s https://my.bdfz.net/api/version | jq -r .version     # 依赖枢纽存活
curl -s https://pulse.bdfz.net/api/meta \
  | jq '.. | objects | select((.host? // "") == "weibian.bdfz.net")'
curl -s 'https://pulse.bdfz.net/api/range?range=24h' \
  | jq '.. | objects | select((.host? // "") == "weibian.bdfz.net")'
curl -s -X POST https://apis.bdfz.net/ -H 'Content-Type: application/json' \
  -H 'Origin: https://weibian.bdfz.net' -H 'X-Project-Name: weibian' \
  -d '{"prompt":"用一句话解释「学而时习之」"}' | jq -r '.answer // .data.answer'
```

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

## 8. 最后验证（2026-07-28）

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
| 学程图 | 20 篇章数正确，可展开章格与掌握度点阵 |
| 真题 | 7 组列出；6 组含原文材料者回挂正确（11.26 / 11.22 / 4.5 / 9.6·7.28·7.20 / 17.8），2018 微写作无引文所以保持空映射 |
| 深色模式 | 靛底暖字，对比正常 |
| 离线（emulator-only） | 未登录、无网络时全书可读可练；不能替代仍开放的实体 offline/recovery 矩阵 |

**2026-07-29 追加验证（全部通过）：**

| 项 | 证据 |
|---|---|
| 内容接口上线 | `weibian.bdfz.net/api/health` 返回 512/1045/215/23；落地页可读 |
| 内容包完整性（线上） | manifest.sha256 == bundle 实测 sha256（`fc68413c…fa75`） |
| **历史真实账号契约验证（非 final exact 实机门）** | canonical 授权账号单次登录成功（未重试）；以 App 同形载荷 PUT 一条非计分 `chapter-1` 金丝雀 → `/api/progress?site=weibian` 回读 → 清理；不保留账号、Cookie 或原始学生内容。此记录只证明身份／进度契约，不替代 final exact APK 的实体登录与持久化验收 |
| 进度契约修正 | 发现读写字段名不一致（读 `?site=`、写 `body.siteKey`），客户端原先写 `site` 会 400，已修并实测通过 |
| **平板／大屏（emulator-only）** | 2560×1600 @320dpi 模拟器：原为拉满全宽，已改 `BoxWithConstraints` 定宽 760dp 居中；手机模拟器 1080×2400 布局无回归。不能替代实体平板 adaptive/accessibility 验收 |
| **AI 讲解（App 内）** | 章句页「问先生」实测返回简体白话分点讲解；发现网关回 Markdown 而 App 纯文本渲染，已加提示词约束＋客户端剥离（8 项测试锁定） |
| **签名构建能力** | final v1.1.1 APK 已从 `e623e370…59e20471` 用专用 authority 构建；2,738,032 bytes，SHA-256 `de47da19…8da67`，signer continuity 与包内 `assets/content.json` 已验证 |
| **发布顺序执行** | immutable APK 与 `release.json` 已先上传并公开逐字节读回；`latest.json`、GitHub Release 与 landing 尚未切换，故 v1.1.1 尚未成为 current／accepted release |
| **自检更新身份修正** | 公开 v1.0.0 `latest.json.appId` 已于 2026-07-30 从错误 base 修正为 `.direct`，公开 bytes SHA-256 `d50ead93…e1db3` 且逐 byte 读回；这只修复当前清单身份，不代替 code4 两机高版本覆盖验收 |
| 登记 live 分层 | nav、Pulse、canonical portal `i.rdfzer.com` 已 live；Companion 为 `not-applicable` 且无 WebView；User Center v242 registry/feedback 已 100% live 并完成 authenticated/idempotent canary 与 representative fan-out smoke；v240 是 exact rollback，clean source reconciliation 仍开放 |

**2026-07-29 正式接管只读复核：**

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

**2026-07-29 production-candidate 追加证据：**

| 项 | 证据 |
|---|---|
| 实体手机 code 3 baseline | 登记的 LE2120 与 IN2020 均安装 byte-identical final code 3 `de47da19…8da67`；package/signer 一致，既有 first-install identity 与 App 资料保留，作为 code4 原位更新起点 |
| Profile 闪退修复 | 同章同时出现在收藏和笔记时，旧版以重复 `chapterId` 作为同一 `LazyColumn` key 而 crash；现已改为 section-namespaced key，并在实体 OnePlus 的 pre-final code 3 候选完成“我”页整页反复滚动，scoped crash buffer 无本 App fatal |
| AI（实体 App） | OnePlus pre-final code 3 候选的 AI 讲解和非敏感高考批改路径均返回可用结果；不代表 final exact APK 或其余实体门已通过 |
| 原子内容发布 | `ContentReleaseFilesTest` 覆盖 staged → active、previous 保留和中断恢复 |
| 差量契约 | B bundle `4a97b261…e3703`（871,334 bytes）与 A→B delta `83d407be…8b1f`（259 bytes）已 immutable 上传并公开精确读回；LE2120 与 IN2020 都通过真实 delta、故意拒绝 delta 后整包回落、重启 readback 与 stable A 恢复；canary 已撤流 |
| Worker | current `e16da332-cbb5-46fd-82c8-ae7a6d4c69c0` 100%；immediate rollback `32f8dd97-9d50-49e1-a0cf-9f1277dd0c92`；ranking v2 health、匿名／失效会话、content/landing/R2 路径均 live readback 通过 |
| 安全基线 | 明确禁止 cleartext；JSON 2 MiB 上限；同步重试有界；排行榜 secret 缺失 fail closed；gitleaks 无命中 |
| 内容权利 | owner 明确授权；`CONTENT_RIGHTS_RECEIPT.md` 入档 |
| 身份/签名契约 | `IDENTITY_ADR.md` 接受有限 native adapter；release 仅接受 `WEIBIAN_ANDROID_*`；Direct manifest 精确匹配 `.direct` |
| signing authority | 唯一 authority 为 `/Users/ylsuen/.android/weibian-release.env`；final `de47da19…8da67` 以 `set -e`、`--no-daemon` 构建，unsigned 输出被拒收 |
| portal / Companion | canonical portal `i.rdfzer.com` 200；两个 bdfz alias 522 为非 canonical；Companion disposition `not-applicable`、无 Weibian WebView |

**尚未验证（明确缺口，不假装已做）：**

- [ ] **本次 Weibian 大屏替代门**：owner 已明确批准仅本 release 使用登记
      手机 reversible forced expanded-layout 完成 adaptive/accessibility；
      通过后必须恢复 size/density/rotation/proxy/font/keep-awake。不得把这项
      例外泛化为舰队未来 release 可免实体平板。
- [ ] **应用内反馈** physical App → API → aggregate D1 → Telegram 通知回执。
- [x] **两台实体手机差异内容下载／整包回落／重启**：LE2120 与 IN2020
      均通过真实 delta；故意错误 digest 均被拒绝并自动 full fallback；
      active bytes/SHA 精确，force-stop/cold restart 后持久化；测试后 stable
      内容测试后稳定 A 已恢复；当前综合 Worker
      `e16da332-cbb5-46fd-82c8-ae7a6d4c69c0` 为 100%。
- [ ] **内容 previous/corrupt-active rollback**：上述实机证据未故意破坏
      active slot，仍须证明自动恢复 previous；不得把 full fallback 扩写成
      three-slot rollback。
- [ ] **实体 offline/recovery 矩阵**：断网启动、读练、恢复联网、
      force-stop/rotation/multi-window 与 scoped logcat 尚未闭环。
- [ ] **两台实体手机的 final exact APK**：舰队登记的 OnePlus 9 Pro
      `LE2120`（hardware serial `c5467d2b`）与 OnePlus 8 Pro `IN2020`
      （hardware serial `6393cccf`）都必须安装 byte-identical 最终签名
      APK，并逐台通过 package 唯一性、覆盖升级、cold/foreground/Back、
      核心/榜单、登录/重启、本机资料/Session/outbox/content version 持久化、
      离线/恢复、反馈、自更新与 scoped fatal/ANR；任一台缺席或任一门失败
      即 fail closed，模拟器不得替代。
- [ ] **共享枢纽技术债（非本 App lifecycle 硬门）**：User Center v242
      registry/feedback 已 live，v240 是 exact rollback；dirty/stale source
      必须在未来任何普通 User Center deploy 前归并到经审 clean Git source。
- [ ] **v1.1.2 / code 4 最终 release/self-update**：旧 v1.1.1 immutable
      APK 与 `release.json` 只能保留为历史 staging，不得升格；公开
      `latest.json` 仍是 v1.0.0，但 appId 已正确为 `.direct`。须构建
      code 4、按 pointer-last 完成三面 byte parity，并在两台实体 Direct App
      读回高版本清单及覆盖升级。

以下不是 open gate：

- canonical portal `i.rdfzer.com` 已 200；两个 bdfz 522 域只是非 canonical alias。
- Companion 已明确 `not-applicable`，没有 Weibian WebView service。

> 模拟器证据一律标注为 **emulator-only**。
> 认证与同步必须用真实账号端到端回读后才能声称可用；界面上有登录框不算同步证据。

**最后验证人／日期**：Codex production-candidate 验证，2026-07-29。
实体手机既有证据覆盖两台登记手机的 exact code 3 baseline 与内容恢复，不覆盖
当前功能变更后的 final exact code 4 APK；code4 仍须两台逐台验收。canonical
新登录/登出、physical feedback、offline/content/rotation/multi-window、
本次 phone expanded-layout 替代与 release 仍未闭环。
浏览器、R2、GitHub、
Wrangler、Pulse 和 CI 证据不替代剩余实体 Android 验收。

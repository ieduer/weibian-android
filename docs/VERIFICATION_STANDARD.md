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
  :app:lintDirectDebug \
  :app:assembleDirectDebug
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
- release build 不使用 `set -e`／`--no-daemon`，或把文档变更前的 interim
  digest 当作 final；
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
| **真实账号端到端** | canonical 授权账号单次登录成功（未重试）；以 App 同形载荷 PUT 一条非计分 `chapter-1` 金丝雀 → `/api/progress?site=weibian` 回读 → 清理；不保留账号、Cookie 或原始学生内容 |
| 进度契约修正 | 发现读写字段名不一致（读 `?site=`、写 `body.siteKey`），客户端原先写 `site` 会 400，已修并实测通过 |
| **平板／大屏** | 2560×1600 @320dpi：原为拉满全宽，已改 `BoxWithConstraints` 定宽 760dp 居中；手机 1080×2400 布局无回归 |
| **AI 讲解（App 内）** | 章句页「问先生」实测返回简体白话分点讲解；发现网关回 Markdown 而 App 纯文本渲染，已加提示词约束＋客户端剥离（8 项测试锁定） |
| **签名构建能力** | 专用 signer certificate continuity 与包内 `assets/content.json` 已验证；最终 v1.1.1 artifact 仍须在本轮文档提交后的 clean commit 上重建，interim digest 不作 final |
| **发布顺序设计** | fail-closed 顺序已写入标准；v1.1.1 尚未依此发布或接受 |
| **自检更新旧结论撤回** | 当前公开 v1.0.0 `latest.json.appId=net.bdfz.weibian`，与 Direct package `.direct` 不符；HTTP 200／旧截图不能算 self-update acceptance |
| 登记 live 分层 | nav、Pulse、canonical portal `i.rdfzer.com` 已 live；Companion 为 `not-applicable` 且无 WebView；User Center registry canary／fan-out 仍开放 |

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
| code checkpoint | `0f2dae26ee84b676a401cad70fa088a8fd8eaac6`；55/55 Android unit tests、Worker 7/7 tests、lint/build 通过 |
| CI | GitHub Actions run `30464811968`, attempt 2, success；这是 code checkpoint，不是最终 release commit |
| 落地页 | desktop 与 390×844 响应式布局通过；390 CSS viewport 无水平溢出 |
| Pulse | meta/range 收录 `weibian.bdfz.net`，source `worker_analytics`，read-only 观察为 0 errors |
| portal | canonical `i.rdfzer.com` 返回 200 且有正确入口；`allinone.bdfz.net`、`portal.bdfz.net` 是非 canonical 522 别名 |

**2026-07-29 production-candidate 追加证据：**

| 项 | 证据 |
|---|---|
| 实体手机覆盖升级 | 指定 OnePlus IN2020 实机（task-scoped ADB serial 不进入公开仓库）：由公开 code 1 经候选 code 2 覆盖至 v1.1.1 code 3；`firstInstallTime` 不变，本地收藏、笔记和学习记录保留 |
| 实体手机真实身份 | canonical 账号经 App 登录一次，重启 session 保留；User Center aggregate 从 0 变 1，`site_key=weibian` 读回 1 row/1 user |
| 排行榜 | App 同步后 `weibian-rankings` D1 写入 1 条 HMAC 假名快照；公开榜不暴露账号或姓名 |
| Profile 闪退修复 | 同章同时出现在收藏和笔记时，旧版以重复 `chapterId` 作为同一 `LazyColumn` key 而 crash；现已改为 section-namespaced key，并在实体 OnePlus code 3 完成“我”页整页反复滚动，scoped crash buffer 无本 App fatal |
| AI（实体 App） | OnePlus code 3 的 AI 讲解和非敏感高考批改路径均返回可用结果；不把网络成功泛化为其余实体门已通过 |
| 原子内容发布 | `ContentReleaseFilesTest` 覆盖 staged → active、previous 保留和中断恢复 |
| 差量契约 | `weibian-content-delta-v1` 重建和 hash/size/base 拒绝由测试锁定；线上 manifest 暂为 `deltas: []`，因 v1.0.0 与候选包内容 hash 相同 |
| Worker | current `64f3c319-e3b8-429e-a49c-1e333fd2e15d`；health、manifest、ranking、landing rights、R2 immutable content/v1.0.0 download 与非法 delta 拒绝均 live readback 通过 |
| 安全基线 | 明确禁止 cleartext；JSON 2 MiB 上限；同步重试有界；排行榜 secret 缺失 fail closed；gitleaks 无命中 |
| 内容权利 | owner 明确授权；`CONTENT_RIGHTS_RECEIPT.md` 入档 |
| 身份/签名契约 | `IDENTITY_ADR.md` 接受有限 native adapter；release 仅接受 `WEIBIAN_ANDROID_*`；Direct manifest 精确匹配 `.direct` |
| signing authority | 唯一 authority 为 `/Users/ylsuen/.android/weibian-release.env`；已有 clean-signer build 仅为 interim，最终文档提交后必须以 `set -e`、`--no-daemon` 重建并拒收 unsigned 输出 |
| portal / Companion | canonical portal `i.rdfzer.com` 200；两个 bdfz alias 522 为非 canonical；Companion disposition `not-applicable`、无 Weibian WebView |

**尚未验证（明确缺口，不假装已做）：**

- [ ] **实体平板** adaptive/accessibility 验收；现有 2560×1600 证据为 emulator-only。
- [ ] **应用内反馈** physical App → API → aggregate D1 → Telegram 通知回执。
- [ ] **差异内容版本**下载、差量/整包回落、重启和 previous rollback；当前仅有确定性测试。
- [ ] **实体 offline/recovery 矩阵**：断网启动、读练、恢复联网、
      force-stop/rotation/multi-window 与 scoped logcat 尚未闭环。
- [ ] **User Center registry canary**：须完成 clean hub deploy、live registry
      readback、真实最小金丝雀与 representative fan-out smoke。
- [ ] **v1.1.1 最终 release/self-update**：当前没有 final APK/hash/size；
      公开 `latest.json` 仍是 v1.0.0 且 `appId` 错写为 base package。须在最终
      clean commit 重建同 signer APK，按 immutable-first/pointer-last 发布，
      核对 R2/GitHub/`i.rdfzer.com` bytes，并在实体 Direct App 完成升级。

以下不是 open gate：

- canonical portal `i.rdfzer.com` 已 200；两个 bdfz 522 域只是非 canonical alias。
- Companion 已明确 `not-applicable`，没有 Weibian WebView service。

> 模拟器证据一律标注为 **emulator-only**。
> 认证与同步必须用真实账号端到端回读后才能声称可用；界面上有登录框不算同步证据。

**最后验证人／日期**：Codex production-candidate 验证，2026-07-29。
实体手机证据只覆盖上表列出的 OnePlus code 3 profile／AI 路径；physical
feedback、offline/content/tablet/release 仍未闭环。浏览器、R2、GitHub、
Wrangler、Pulse 和 CI 证据不替代剩余实体 Android 验收。

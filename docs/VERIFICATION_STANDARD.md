# 韦编 · 核查标准（八点）

依 `runbooks/bdfz_project_matrix_and_interdependencies.md` §8 —— 本机强制。
**跑完这份标准才算"改完"**，"构建成功"不算。

---

## 1. 事实来源（Source of Truth）

| 资产 | 位置 |
|---|---|
| App 源码 | `/Users/ylsuen/CF/lunyu-yizhu-android`（本仓库） |
| 内容管线 | `content/build_content.py` |
| 内容语料上游 | `CF/lunyu/data/dialogues.json`、`CF/lunyu-battle/src/data/`、`CF/gaokao/data/all.json`、`CF/gks/data/papers/` |
| 内容接口 | Worker `weibian-content` → `weibian.bdfz.net` |
| 身份与进度 | `my.bdfz.net`（`bdfz-user-center`，D1 `bdfz-user-center-db`）—— **本项目只是调用方** |
| AI | `apis.bdfz.net` —— **本项目只是调用方，无密钥** |
| APK 发布 | R2 `blog-images` → `img.bdfz.net/apps/weibian-android/` |
| 签名密钥 | `~/.android/`（600，不入库），仅记别名与指纹 |

**上游语料是只读的。** 本项目从不写 `CF/lunyu`、`CF/lunyu-battle`、`CF/gaokao`、`CF/gks`。

## 2. 健康探针

```bash
curl -s https://weibian.bdfz.net/api/health | jq
# 期望 {"ok":true,"service":"weibian-content","contentVersion":"...","counts":{...}}

curl -s -o /dev/null -w '%{http_code}\n' https://img.bdfz.net/apps/weibian-android/latest.json
# 期望 200（首次发布前为 404，属预期）

curl -s https://my.bdfz.net/api/version | jq -r .version     # 依赖枢纽存活
curl -s -X POST https://apis.bdfz.net/ -H 'Content-Type: application/json' \
  -H 'Origin: https://weibian.bdfz.net' -H 'X-Project-Name: weibian' \
  -d '{"prompt":"用一句话解释「学而时习之」"}' | jq -r '.answer // .data.answer'
```

## 3. 契约核查

```bash
# 内容包与清单 sha256 必须一致
test "$(curl -s https://weibian.bdfz.net/api/content/bundle | shasum -a 256 | cut -d' ' -f1)" \
   = "$(curl -s https://weibian.bdfz.net/api/content/manifest | jq -r .sha256)" && echo SHA-OK

# 内容管线自校验（章数/篇章数/答案/诊断/引用）
python3 content/build_content.py --check

# 内容与领域逻辑回归（26 项）
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:testDirectDebugUnitTest
```

更新清单契约 `bdfz-android-update-v1` 的客户端校验在 `update/AppUpdateManager.kt`；
进度契约字段（`itemKey=chapter-<id>`、`state`、`progressPercent`、`meta.*`）
在 `data/LearningRepository.kt#enqueue` 与 `network/ApiClient.kt#pullProgress`，
**两边必须同时改**。

## 4. 部署命令与禁止事项

部署见 [DEPLOYMENT.md](DEPLOYMENT.md)。**禁止**：

- 覆盖任何已公开读过的内容寻址 APK 对象；
- 用更低 versionCode 做"回滚"；
- 在 App 内内置任何模型密钥或绕过 `apis.bdfz.net`；
- 在本项目里新建用户表、密码、注册或找回流程；
- 修改上游语料仓库（`CF/lunyu`、`CF/lunyu-battle`、`CF/gaokao`、`CF/gks`）；
- Room 用 `fallbackToDestructiveMigration`；
- 把签名口令、Cookie、会话 id 写进日志、报告或提交。

## 5. 依赖回归

本项目是**叶子**，不是枢纽 —— 它消费用户中心、AI 网关、img 图床，不被别人消费。
因此常规改动**不需要**扇出验证。

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

## 7. 回滚

见 [DEPLOYMENT.md](DEPLOYMENT.md) 第三节。锚点：
Worker `wrangler rollback`；`latest.json` 指针；更高 versionCode 的修复版。

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
| 真题 | 7 组列出，章句回挂正确（11.26 / 11.22 / 4.5 / 9.6·7.28·7.20 / 17.8） |
| 深色模式 | 靛底暖字，对比正常 |
| 离线 | 未登录、无网络时全书可读可练（同步 Worker 直接成功返回） |

**2026-07-29 追加验证（全部通过）：**

| 项 | 证据 |
|---|---|
| 内容接口上线 | `weibian.bdfz.net/api/health` 返回 512/1045/215/23；落地页可读 |
| 内容包完整性（线上） | manifest.sha256 == bundle 实测 sha256（`fc68413c…fa75`） |
| **真实账号端到端** | 单次登录成功（slug=suen，未重试）；以 App 完全相同的载荷 PUT 一条 `chapter-1` 金丝雀 → `/api/progress?site=weibian` 回读到 `state=in_progress, progressPercent=35, read=true` → 删除回零；会话 cookie 已从磁盘清除 |
| 进度契约修正 | 发现读写字段名不一致（读 `?site=`、写 `body.siteKey`），客户端原先写 `site` 会 400，已修并实测通过 |
| **平板／大屏** | 2560×1600 @320dpi：原为拉满全宽，已改 `BoxWithConstraints` 定宽 760dp 居中；手机 1080×2400 布局无回归 |
| **AI 讲解（App 内）** | 章句页「问先生」实测返回简体白话分点讲解；发现网关回 Markdown 而 App 纯文本渲染，已加提示词约束＋客户端剥离（8 项测试锁定） |
| **签名 release 包** | `assembleDirectRelease` 出包 2.6 MB；证书 SHA-256 `a40f3956…1282`；R8 压缩后 `assets/content.json` 871,333 字节完整在包内 |
| **发布链路** | 按 fail-closed 顺序传 R2：内容寻址 APK → release.json → 别名 → **公开读逐字节回验一致后**才写 `latest.json` |
| **自检更新端到端** | 安装 release 包后「我 → 关于」实测拉取 `img.bdfz.net/apps/weibian-android/latest.json`，通过 schema／包名／versionCode 校验，正确显示「已是最新版本」 |
| 五面登记 | 用户中心 SITE_REGISTRY／nav sites.json／门户 portalGroups／Companion SERVICES／pulse sites.js 均已加 `weibian`；nav 与 pulse `/api/meta` 线上核验命中 |

**尚未验证（明确缺口，不假装已做）：**

- [ ] **真机**安装与升级覆盖安装（全部证据均为 **emulator-only**）
- [ ] **升级覆盖**：versionCode 2 覆盖 1 后本地记录完好（当前只有 v1，无从验证）
- [ ] **应用内反馈**投递与 Telegram 通知回执（表单已实现，未发过真实工单）
- [ ] **AI 高考批改**在 App 内实测（讲解已通，批改走同一网关同一代码路径，但未单独点过）
- [ ] **内容热更新**端到端（线上内容版本与随包版本相同，未构造差异版本触发替换）
- [ ] 旋转、force-stop 后持久化的系统性走查（已知冷启动与切页签持久化正常）
- [ ] **`bdfz-user-center` 的 `weibian` 登记条目尚未部署** —— 该仓库有 32 个文件、
      13,686 行的他人在途改动，部署会一并发布，属越权。源码已改好，待其在途工作收口后随下次发布带上。
      不影响功能：`/api/progress` 不校验 siteKey 是否登记，进度读写已实测通过。
- [ ] **门户生产域 `allinone.bdfz.net` 返回 522**（`portal.bdfz.net` 同样 522，
      且该域也取不到既有的 `kz.bdfz.net` 条目），系**既存故障**、与本次改动无关；
      Pages 部署本身成功，预览地址可见新条目。

> 模拟器证据一律标注为 **emulator-only**。
> 认证与同步必须用真实账号端到端回读后才能声称可用；界面上有登录框不算同步证据。

**最后验证人／日期**：本次会话，2026-07-28（模拟器 Android 15 arm64）。

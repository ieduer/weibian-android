# 韦编 · 论语译注

> 韦编三绝 —— 孔子读《易》，编简的皮绳断了三次。
> 这个 App 的目标只有一个：让一个人真正读完、读懂、记住、并能运用《论语》全部 **512 章**。

一个**原生 Android 应用**（Kotlin + Jetpack Compose），不是 WebView 套壳。

当前 lifecycle 为 `published-limited`：公开版仍是 v1.0.0 / versionCode 1；
v1.1.1 / versionCode 3 的历史候选 APK 与 `release.json` 已按内容寻址公开，
但 `latest.json`、GitHub Release 与落地页尚未切换，因此尚未成为当前发布版，
也尚未接受。当前源码的下一候选身份是 v1.1.2 / versionCode 4；它必须重新
构建、签名并完成全部实体设备门，不能把既有 code 3 staging 直接升格。历史检查点
`e623e370a60bff33609e8bf5ad2748f559e20471` 的 GitHub Actions
[run 30466463323](https://github.com/ieduer/weibian-android/actions/runs/30466463323)
已通过；APK 为 2,738,032 bytes，SHA-256
`de47da19562515049769c872f738975d8000091f9295f40e691d2928fe18da67`，
signer certificate SHA-256 为
`a40f3956296d09ca2c6d8c3ec23f4f1d5470cb8ca6a5d4a69a9f19eb39941282`。
当前公开 `latest.json` 仍指向 v1.0.0；历史错误 `appId=net.bdfz.weibian`
已于 2026-07-30 修正为 APK 真实身份 `net.bdfz.weibian.direct`，公开 bytes
逐 byte 读回通过。code4 尚未发布，两台实体 Direct App 的高版本覆盖更新
仍未完成，因此**不得把清单修正扩写成 code4 应用内自更新已验证**。

---

## 这是什么

| | |
|---|---|
| 正文 | 杨伯峻《论语译注》全本 —— **512 章**原文 + 译文 + **1045 条注释** |
| 题库 | **215 道**人工精编题（八种题型，每个错项都有诊断）+ 由语料确定性生成的原文题 |
| 真题 | 北京卷《论语》经典阅读 **7 组 23 道**（2015／2018／2019／2020／2021／2023 + 2018 微写作），含 AI 批改 |
| 概念 | 17 个核心概念、15 位孔门人物，与章句互相索引 |
| 界面 | 简体中文；原文保留文言原貌，译注分层展开 |

### 与本工作区其他《论语》项目的关系

**这是一个全新的独立原生 App，与下列项目互不相干**，只在**构建期只读地**取用它们的语料：

| 项目 | 是什么 | 与本项目的关系 |
|---|---|---|
| `kz.bdfz.net`（`CF/lunyu`） | 静态网页版 AI 论语阅读 | 提供《论语译注》全文语料（只读） |
| `ly.bdfz.net`（`CF/lunyu-battle`） | React 网页对战游戏 | 提供 215 道题库与概念表（只读） |
| `CF/lunyu-battle-android` | 上者的 **Capacitor WebView 套壳** | **无关**；本项目是纯原生重做 |
| `CF/recite-android` | 琅琅背诵 App | 参考其用户系统与更新架构 |

本项目独占：目录 `lunyu-yizhu-android/`、Kotlin namespace
`net.bdfz.weibian`、Direct/Play 共用 canonical package
`net.bdfz.weibian.direct` 与连续签名身份、siteKey `weibian`、域名
`weibian.bdfz.net`、更新通道
`img.bdfz.net/apps/weibian-android/`。

---

## 学习设计

### 三层掌握

1. **原文掌握** —— 识文（译文↔原文）、补字（挖空）、连句（乱序重排）
2. **注释掌握** —— 释词（杨伯峻注释直接命题）、核心概念、易混辨析
3. **综合理解** —— 章句关联、观点冲突、人物弟子、现实应用、误解识别

前两层由语料**确定性生成**（同章同轮次题目稳定，换轮次才换题，可复习）；
第三层来自人工精编题库。

### 掌握度不是"读过"

单章 0–100 分：读原文 20 ＋ 读译注 15 ＋ 答题正确率 45（需 ≥3 次作答才给满）＋ 复习 20。
≥80 判为掌握。**一路划到底拿不到掌握**，答对一题也不行 —— 这是刻意的。

### 段位

段位名全部取自《论语》本文：

`童蒙 → 志学 → 束脩 → 升堂 → 入室 → 博文 → 约礼 → 不惑 → 从心`

每段三星，集满第三星即晋段。修为只增不减，是本机即时学习反馈；单列的
“榜单”只统计服务端按当前授权内容
核验的人工精编题首次记录作答。每日榜按服务端北京时间接收日计入，总榜按
核验答对数累计；榜单积分／等级不与本机修为／段位混用。

---

## 快速开始

```bash
git clone <repo> && cd lunyu-yizhu-android
node scripts/bootstrap_public_content.mjs
echo "sdk.dir=$ANDROID_HOME" > local.properties
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:assembleDirectDebug
```

APK 在 `app/build/outputs/apk/direct/debug/`。需要 **JDK 21**、Android SDK 37。

clean clone 先按 checked-in lock 从已授权的不可变内容对象恢复 exact bytes。
只有受控上游语料变更时才重建内容包：

```bash
/Users/ylsuen/.venv/bin/python content/build_content.py
```

详见 [开发指南](docs/DEVELOPMENT.md)。

---

## 架构一句话版

```
内容（不可变、版本化）          学习记录（可变、离线优先）
assets/content.json      ┐      Room: chapter_progress / task_attempts
weibian.bdfz.net 热更新  ┘             daily_stats / gaokao_attempts
        │                                      │
        └──→ ContentBundle (内存索引) ←────────┘
                     │
              LearningEngine（出题）
                     │
                 Compose UI
                     │
        my.bdfz.net（身份 + 进度同步）
        apis.bdfz.net（AI 讲解与批改，App 内无任何密钥）
```

**内容更新不需要发新 APK**；APK 自检更新走 `bdfz-android-update-v1` 契约。

详见 [架构文档](docs/ARCHITECTURE.md)。

---

## 文档

| 文档 | 内容 |
|---|---|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | 分层、数据流、内容管线、同步与冲突处理 |
| [DEVELOPMENT.md](docs/DEVELOPMENT.md) | 本地环境、构建、调试、测试、模拟器 |
| [DEPLOYMENT.md](docs/DEPLOYMENT.md) | APK 签名发布、内容 Worker 部署、GitHub Release |
| [MAINTENANCE_MANUAL.md](docs/MAINTENANCE_MANUAL.md) | 运维手册与故障排查（登录／同步／更新／迁移） |
| [VERIFICATION_STANDARD.md](docs/VERIFICATION_STANDARD.md) | 八点核查标准（本机强制） |
| [SECURITY_REVIEW.md](docs/SECURITY_REVIEW.md) | v1.1.2／code 4 source freeze 安全审查与剩余 release 门 |
| [IDENTITY_ADR.md](docs/IDENTITY_ADR.md) | Direct 渠道身份流程决策 |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 贡献流程与代码约定 |
| [art/README.md](art/README.md) | 美术资产体系与授权 |

---

## 内容来源与授权

- **正文与译注**：杨伯峻《论语译注》（中华书局）。《论语》原文属公有领域；
  译文与注释的著作权归原出版方；本项目已取得公开发布授权，授权依据见
  [`docs/CONTENT_RIGHTS_RECEIPT.md`](docs/CONTENT_RIGHTS_RECEIPT.md)。
- **高考真题**：北京卷历年语文真题，取自本机既有真题库。
- **代码**：MIT（见 [LICENSE](LICENSE)）。**授权仅涵盖代码，不涵盖上述语料**。
- **美术资产**：全部原创矢量绘制，无第三方素材，见 [art/README.md](art/README.md)。

---

## 已知缺口

诚实记录，不藏着：

1. **注册**：用户中心已在服务端关闭注册（`registration-disabled`），且运维标准禁止项目自建用户表。
   因此 App 只提供**希悦账号登录**与**完整离线游客学习**；注册界面预留，服务端一旦开放即可用。
2. **上游语料缺注**：雍也 6.7、6.26 两章正文有注释标记但上游 `dialogues.json` 缺对应注释条目。
   这两处标记按纯文本渲染，不给点了没反应的目标。
3. **真题覆盖**：北京卷设《论语》大题的年份只有 6 年（其余年份考《红楼梦》），已全部收录，不是遗漏。
4. **验收范围**：两台登记实体手机 LE2120 与 IN2020 都已安装 byte-identical
   v1.1.1 / code 3 final APK `de47da19…8da67`，保留 package、first-install
   identity 与 App 资料；“我”页重复 key 闪退已完成反复整页滚动回归。两机还
   完成真实 A→B delta、故意拒绝 delta 后整包回落、重启 readback、稳定 A
   恢复和单一 Weibian package 核对。它们仍只是 code 4 的 prior-version
   baseline；physical feedback、code3→code4 原位更新、code4 离线／恢复、
   rotation／multi-window 与本次获批的手机 expanded-layout 平板替代验收仍
   必须在 final exact code 4 上完成。User Center v242 已 100% live；v240 是
   精确反馈 rollback。
5. **发布边界**：v1.1.1 的 immutable APK 与 `release.json` 已公开并逐字节
   验证，但它已被当前 v1.1.2 / code 4 源码候选取代，不能再移动为 current。
   公开 `latest.json` 仍指向 v1.0.0，但其 `appId` 已修正为 canonical
   `net.bdfz.weibian.direct` 并由两台 code 3 App 显示“已是最新版本”。正确
   code 4 的 immutable APK、`latest.json`、GitHub Release、落地页和实体
   self-update 尚未发布／验收。差异内容 canary 已完成双机验收并撤流；ordinary
   Worker 已恢复稳定 A 内容。
6. **公开入口**：canonical portal 是 `https://i.rdfzer.com`，当前返回 200；
   `allinone.bdfz.net` 与 `portal.bdfz.net` 是非 canonical 别名，当前 522 不作为
   本 App 的发布入口。Companion disposition 为 `not-applicable`，不得新增
   Weibian WebView service。

---

## 界面

| 今日 | 章句 | 点注释 |
|---|---|---|
| ![](docs/screenshots/01-今日.png) | ![](docs/screenshots/02-章句.png) | ![](docs/screenshots/03-点注释.png) |

| 挑战反馈 | 学程图 | 高考真题 | 深色模式 |
|---|---|---|---|
| ![](docs/screenshots/04-挑战反馈.png) | ![](docs/screenshots/05-学程图.png) | ![](docs/screenshots/06-高考真题.png) | ![](docs/screenshots/07-深色模式.png) |

> 截图取自 Android 15 模拟器（arm64），标注为 emulator-only。

平板（2560×1600）正文限宽居中：

![](docs/screenshots/08-平板布局.png)

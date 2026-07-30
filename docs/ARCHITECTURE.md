# 架构

## 一条主线：内容与记录彻底分开

这是整个设计里唯一真正重要的决定。

| | 内容 | 学习记录 |
|---|---|---|
| 是什么 | 512 章正文译注、题库、真题、概念人物 | 读到哪、答对没、错题、笔记、收藏 |
| 可变性 | **不可变**，整包替换 | 持续追加 |
| 存放 | `assets/content.json` ＋ `filesDir/content/`（热更新） | Room `weibian-learning.db` |
| 版本化 | contentVersion（内容 sha256 前 16 位） | Room schema version |
| 丢了会怎样 | 重新下发即可 | **不可再生** |

**为什么不把内容也放进 Room**：内容一旦进库，每次内容更新都变成一次数据库迁移；
而内容更新是常态（改错字、补注释、加真题）。分开之后，内容更新是"换一个文件"，
学习记录一行都不动。

对应地，Room 建库时**不用** `fallbackToDestructiveMigration` —— 学习记录不可再生，
宁可迁移失败抛出来让人处理，也不能悄悄抹掉。

---

## 分层

```
ui/            Compose 界面 + WeibianViewModel（唯一状态持有者）
 ├─ screens/   今日 / 学程 / 章句 / 挑战 / 真题 / 我
 └─ components/ 朱印、细进度条、段位星、纸面卡片、掌握度点

domain/        纯 Kotlin，无 Android 依赖，可直接单测
 ├─ Progression   段位阶梯、掌握度评分、修为规则
 ├─ LearningEngine 出题（人工题 + 生成题）
 └─ Achievements  成就判定

data/          Room 实体 / DAO / LearningRepository（唯一写入口）
content/       ContentBundle（内存索引）+ ContentStore（三槽发布）+ ContentDelta
network/       ApiClient（用户中心 / 内容接口 / AI 网关）
security/      SecureSessionStore（AES-GCM + AndroidKeyStore）
sync/          ProgressSyncWorker（WorkManager 周期同步）
update/        AppUpdateManager（bdfz-android-update-v1）
```

`domain/` 不依赖 Android 是有意的：段位、掌握度、出题这些最需要被测试的逻辑，
在 JVM 单测里就能跑完，不必起模拟器。

---

## 内容管线

```
CF/lunyu/data/dialogues.json            541 行 ──┐
CF/lunyu-battle/src/data/bank/*.ts      215 题 ──┤
CF/lunyu-battle/src/data/{concepts,figures}.ts ─┤→ content/build_content.py
CF/gaokao/data/all.json     (key=lunyu)       ──┤        │
CF/gks/data/papers/*chinese*.json             ──┘        ↓
                                              content/dist/{content,manifest}.json
                                                         │
             ┌──────────────────────────┼──────────────────────────┐
             ↓                          ↓                          ↓
 app/src/main/assets/        R2 immutable bundles       worker/public/ manifest
 （随 APK 附带）              （按 version/hash 保留）       （审核后的稳定指针）
```

管线在构建期做了三件不显然的事：

**1. 去重保别名。** 源数据 541 行只有 512 章（乡党 10.25–10.27、先进 11.1–11.26 各出现两次）。
512 才是杨伯峻本的定数。重复条目折叠成**别名**而非删除，因此 `kz`／`ly` 两站已保存的旧
章 id 仍能解析回现行章 —— 迁移既有进度时不会指空。

**2. 修复注释序号。** 上游约半数章节的注释序号有转录讹误：為政 2.4 第六条本应 ⑹ 却写成 ⑷
（重号且缺号）、公冶長 5.7 首条整个漏掉 ⑴、雍也 6.5 第三条写成 ⑵。
正文里的标记若按讹误序号查表就会落空，点注释的交互随之失效。

修法不是按"第几条"对齐 —— 杨伯峻常在首条注释一个正文未加标记的词
（如 學而 1.1 的 ⑴「子」），按位置对齐会整体错位一格。
实际做法是把序号**修复成严格递增**：解析出的序号只有大于前一条时才采信，否则按前一条 +1 补。
效果：正文标记可解析率 **207/409 → 407/409**。剩下 2 章（雍也 6.7、6.26）是上游确实缺注释，
以 `unresolvedMarkers` 标出，前端按纯文本渲染。

**3. 真题回挂章句。** 高考材料与原文的关系有三种形态：整章引用（阳货 17.8 六言六蔽）、
只截取长章一段（2015 侍坐篇只引篇末对话）、转录有讹字（2019 材料作「贫与残」）。
单向包含判定只能处理第一种，因此改用**最长公共子串**：重合覆盖该章一半以上，
或连续重合 ≥25 字，即判命中。六组含原文材料的真题全部回挂；2018 微写作
只有命题要求、没有引文，明确保留为空映射，不能伪造章句关系。

---

## 出题

```
一章的任务 = 该章的人工精编题（若有） + 生成题（补字 / 释词 / 识文 / 连句）
```

生成用**固定种子**（章 id × 常数 + 轮次），因此：

- 同一章同一轮次，题目稳定 —— 可以复习，不会每次进来换一套；
- 轮次递增（每答 4 题进一轮），题目换一批 —— 不会永远只有那几道。

自适应排程 `adaptiveQueue` 的顺序是：**难点章 → 没练过的章 → 已掌握章（保持性复习）**。

生成题只从既有语料派生，不凭空编造内容 —— 干扰项一律取自别章真实文本／注释。

---

## 身份与同步

身份**一律走 `my.bdfz.net`**（BDFZ 统一用户系统），本 App：

- 不自建用户表、不做本地密码；
- 密码只在登录请求里出现一次，之后只持有会话 Cookie；
- 会话 Cookie 经 **AES-GCM 加密**存放，密钥在 AndroidKeyStore 中不可导出；
- 解密失败一律清空重登，不做降级读取。

同步是**离线优先**的单向管道：

```
学习动作 → 立即写 Room → 同时排进 sync_queue
                              ↓（联网时，WorkManager 每 6 小时 / 手动）
                     先 pull 合并远端，再逐条 push
                              ↓
                     push 成功的才出队，失败的留着下次重试
```

合并策略是**取双方较优者**（read/annotationRevealed 取或，attempts/correct/reviews 取大），
不让同步把本地更好的记录冲掉。未登录时 Worker 直接成功返回 —— 没有账号不是错误状态。

---

## AI

只有两处用 AI，都走 **`apis.bdfz.net` 统一网关**：

1. 章句讲解（"问先生"）—— 现代白话、分点、面向中学生；
2. 高考真题批改 —— 按阅卷标准给分、踩点、遗漏、表达建议。

**App 内不含任何模型密钥**。网关要求 `Origin` 为 bdfz 域，请求带
`X-Project-Name: weibian`、`X-Task-Type`。响应 `answer` 在顶层
（历史上也出现过在 `data.answer`，客户端两种都认）。

AI 失败一律降级为提示文案，作答早已在本地留痕，不会白写。

---

## 更新的两条独立通道

| | 内容更新 | APK 更新 |
|---|---|---|
| 触发 | 启动时静默检查 / 手动"更新内容" | 冷启动、回前台、手动"立即检查"（6 小时限流） |
| 来源 | `weibian.bdfz.net/api/content/manifest` | `img.bdfz.net/apps/weibian-android/latest.json` |
| 校验 | 精确 schema/host/path + base/target/patch sha256 + size + 可解析性 | schema／精确包名／versionCode 单调递增／不可变 URL／sha256／size |
| 失败 | 差量失败回落完整 bundle；下载失败保留 active/previous；App 仍可用 | 非阻断，只在"关于"里提示暂不可用 |
| 安装 | staged → active 原子发布，保留 previous | **交给 Android 系统安装器**，绝不静默安装 |

Direct 与 Play 是同一安装身份 `net.bdfz.weibian.direct`，共用连续签名
lineage；两者只分离更新传输，不能在同一设备并存成两个 App。

Worker manifest 与发布代码随部署回滚；实际内容 bundle/delta 保存在 R2
内容寻址对象中，`content-releases.js` 只映射已审核版本，因此旧版本 URL 在
新部署后仍可回读。manifest 只列比完整 bundle 小的
`weibian-content-delta-v1` 补丁；没有适用补丁时直接取完整 bundle。

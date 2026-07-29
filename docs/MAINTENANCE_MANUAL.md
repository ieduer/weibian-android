# 运维手册

## 日常

| 周期 | 事项 |
|---|---|
| 每次发布 | 跑完[核查标准](VERIFICATION_STANDARD.md)八点 |
| 内容有更正 | 改上游语料 → `build_content.py` → 同步 assets 与 worker/public → 部署 Worker（无需发 APK） |
| 每季 | 复核签名密钥可用性与备份；核对 `latest.json` 与 R2 对象一致 |
| 上游语料变动 | 跑 `--check`；断言不过说明上游结构变了，先查清再动 |

---

## 故障排查

### 登录

| 现象 | 原因与处理 |
|---|---|
| "登录成功，但服务器没有返回会话" | 用户中心未下发 `bdfz_uc_session`。查 `curl -s https://my.bdfz.net/api/version`；若枢纽异常见 `runbooks/user_center_incident.md` |
| 提示"注册入口已关闭" | **预期行为**。服务端已全局关闭注册（`registration-disabled`），请用希悦账号登录 |
| 反复登录失败 | **不要重试**。用户中心对多次失败会临时锁定账号，等锁定窗口过去或让用户确认密码 |
| 重装后需重新登录 | **预期**。会话密钥在 AndroidKeyStore，不随备份还原；学习记录本身会还原 |
| 登录后进度没过来 | 手动"立即同步"；确认 `/api/progress?site=weibian` 有数据；注意 `itemKey` 形如 `chapter-<id>` |

### 同步

| 现象 | 处理 |
|---|---|
| "待同步 N 条"不下降 | 说明 push 一直失败。查网络与会话是否过期（过期需重登）。队列不会丢，联网后自动冲刷 |
| 两台设备进度不一致 | 合并策略是取双方较优者，不会互相冲掉；先在两边各点一次"立即同步" |
| 未登录学了很久，登录后会覆盖吗 | 不会。本地记录保留，登录后与远端合并 |

### 更新

| 现象 | 处理 |
|---|---|
| "更新检查暂不可用" | 非阻断。查 `curl -I https://img.bdfz.net/apps/weibian-android/latest.json` |
| 检查不到新版 | 自动检查 6 小时限流；点"立即检查"强制。或 `versionCode` 未递增 |
| "更新地址不在允许范围内" | `apkUrl` 必须以 `https://img.bdfz.net/apps/weibian-android/` 开头，客户端硬校验 |
| "更新清单与当前应用不匹配" | `appId` 与包名不符。注意 `direct` 渠道包名带 `.direct` 后缀，清单写基础包名即可 |
| 商店版看不到更新入口 | **预期**：`play` 渠道 `SELF_UPDATE_ENABLED=false`，由商店负责 |
| 内容更新后没生效 | 校验失败会静默回落内置包。查 Worker 的 manifest 与 bundle sha256 是否一致 |

### 数据迁移

| 场景 | 处理 |
|---|---|
| 用户在 `kz`/`ly` 站已有进度 | 内容包保留了 29 条重复章的**别名映射**，旧章 id 可解析回现行章（`ContentBundle.canonicalId`） |
| Room schema 变更 | **必须写 Migration**。禁用 destructive fallback —— 学习记录不可再生 |
| 内容包结构变更 | 升 `schemaVersion`；旧客户端会因解析失败回落内置包，不会崩 |
| 用户换机 | 已登录：`/api/progress` 拉回。未登录：仅靠系统备份，恢复不了就是丢了 —— 这也是引导登录的理由 |

### 内容问题

| 现象 | 处理 |
|---|---|
| 某章点注释没反应 | 查该章 `unresolvedMarkers`。雍也 6.7、6.26 是**上游确实缺注释**的已知缺口 |
| 注释对错了字 | 改上游 `dialogues.json` → 重跑管线 → 部署 Worker。注意序号讹误由管线自动修复，不要手改序号 |
| 某题答案存疑 | 题在 `CF/lunyu-battle/src/data/bank/*.ts`，改完重跑管线；`ContentBundleTest` 会校验答案在选项内且错项有诊断 |
| 真题没挂到章句 | 匹配阈值在 `build_content.py#map_passages`（≥12 字且覆盖半章，或连续 ≥25 字）。微写作类无材料，本就不挂 |

---

## 边界（别做的事）

- 不在本项目新建用户表、密码、注册或找回流程 —— 身份只属于 `my.bdfz.net`。
- 不在 App 内内置模型密钥 —— AI 只走 `apis.bdfz.net`。
- 不写上游语料仓库 —— 它们是其他项目的事实来源。
- 不覆盖已公开读过的内容寻址 APK 对象。
- 不用更低 versionCode 做回滚。
- 日志、报告、提交里不出现口令、Cookie、会话 id、学生内容。

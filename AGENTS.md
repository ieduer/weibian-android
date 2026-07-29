# AGENTS.md — 韦编 · 论语译注 Android

本文件适用于 `/Users/ylsuen/CF/lunyu-yizhu-android`。

## 1. 开工前必读

依序阅读：

1. `/Users/ylsuen/CF/AGENTS.md`
2. `/Users/ylsuen/CF/runbooks/bdfz_project_matrix_and_interdependencies.md`
3. `/Users/ylsuen/CF/runbooks/bdfz_android_app_fleet_operations.md`
4. `/Users/ylsuen/CF/runbooks/bdfz_native_app_development_standard.md`
5. `/Users/ylsuen/CF/runbooks/bdfz_android_app_update_standard.md`
6. 本仓库 `README.md`
7. `docs/ARCHITECTURE.md`
8. `docs/MAINTENANCE_MANUAL.md`
9. `docs/DEPLOYMENT.md`
10. `docs/VERIFICATION_STANDARD.md`
11. `docs/SECURITY_REVIEW.md`

第一次 mutation 前检查本仓库 `git status` 和
`/Users/ylsuen/CF/reports/agent_action_log.jsonl` 的 active ownership。

## 2. 不可混淆的身份

| 字段 | 值 |
|---|---|
| fleetId / appKey | `weibian-android` |
| source repo | `/Users/ylsuen/CF/lunyu-yizhu-android` |
| GitHub | `ieduer/weibian-android` |
| siteKey | `weibian` |
| base / Play package | `net.bdfz.weibian` |
| Direct package | `net.bdfz.weibian.direct` |
| public host / Worker | `weibian.bdfz.net` / `weibian-content` |
| update prefix | `img.bdfz.net/apps/weibian-android/` |
| current public release | v1.0.0 / code 1 |
| lifecycle | `published-limited`，不是 `production-supported` |

它与 `lunyu`、`lunyu-battle`、`lunyu-battle-android`、`recite-android`
都不是同一项目。上游语料只读；不要在本任务顺手改上游站点或共享 hub。

## 3. 约束

- App 核心保持原生；禁止新增 WebView/远端 HTML/DOM 路由作为学习界面。
- public Git 不提交生成的译注语料；已授权分发只走签名 APK 与
  `content/public-content-lock.json` 锁定的不可变内容服务。
- 身份/进度/反馈只走 User Center；AI 只走 `apis.bdfz.net`；APK 不含密钥。
- Room 不得使用 destructive migration；内容回滚不得删除学习记录。
- Direct/Play package、version、signer、R2 prefix 和 manifest 逐项核对；
  不复制其他 App 的 release 命令。
- 不覆盖 immutable APK；坏版本用同 signer、更高 `versionCode` 修复。
- 不覆盖或删除 `apps/weibian-content/releases/` 已发布对象；
  `worker/src/content-releases.js` 只追加审核过的 exact key。
- 当前 public v1.0.0 `latest.json.appId` 的 base package 是已知历史错误；
  v1.1.0 起 Direct 必须精确为 `net.bdfz.weibian.direct`。
- 实体 OnePlus 手机覆盖升级、真实登录、进度和排行榜已通过；当前仍缺实体
  tablet、physical feedback receipt 和真实差异内容更新/回滚证据。补齐且
  User Center registry/portal live 前不得写 production-supported。
- `bdfz-user-center`、Companion、portal/nav/Pulse 或 canonical report
  被其他 active task 持有时只能只读验证。

## 4. 修改与交付

保持 surgical change。内容、App、Worker、registry 和 release 分开提交和
验证；任何 production/config/data mutation 都写 action log。

提交前至少运行：

```bash
/Users/ylsuen/.venv/bin/python content/build_content.py --check
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew \
  :app:testDirectDebugUnitTest \
  :app:lintDirectDebug \
  :app:assembleDirectDebug
```

发布必须执行 `docs/VERIFICATION_STANDARD.md` 全部八点与舰队 release card。

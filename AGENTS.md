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
| Kotlin namespace | `net.bdfz.weibian`（不是安装身份） |
| Direct / Play package | `net.bdfz.weibian.direct`（同一 App、同一签名 lineage） |
| public host / Worker | `weibian.bdfz.net` / `weibian-content` |
| update prefix | `img.bdfz.net/apps/weibian-android/` |
| current public release | Direct R2 / GitHub / production landing v1.1.2 / code 4；deployment `3f5d9c74…` |
| lifecycle | Direct `production-supported`；Play channel 尚未启用 |

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
- public v1.0.0 `latest.json.appId` 的旧 base package 历史错误已于
  2026-07-30 修正并逐 byte 读回；Direct 与 Play 必须继续都精确为
  `net.bdfz.weibian.direct`，不得并存成两个 App。
- v1.1.2 / code 4 的 immutable APK、`latest.apk` 与 `latest.json` 已按
  pointer-last 上线，GitHub v1.1.2 Release 与 R2 bytes 一致；production
  deployment `3f5d9c74…` 已由 `1ce95b1a…@100%` 承载 v1.1.2 landing，
  `e16da332…` 是 exact rollback。当前 Direct lifecycle 为
  `production-supported`。
- 每个候选从舰队登记的 OnePlus 9 Pro `LE2120`（hardware serial
  `c5467d2b`）或 OnePlus 8 Pro `IN2020`（hardware serial `6393cccf`）
  中选定并记录一台实体手机，在该机安装 exact 签名 APK，并通过 package
  唯一性、覆盖升级、cold/foreground/Back、真实登录、核心/榜单、
  离线/恢复、本机资料/Session/outbox/content version 持久化、反馈、自更新
  与 scoped fatal/ANR。平板效果在同机保存基线后，以可逆 expanded
  smallest-width、200% 字级、横竖屏和多窗口验收，最后恢复并读回
  size/density/font/rotation/proxy/keep-awake。第二台手机与模拟器只作补充。
- 当前选定门机为 IN2020；它已完成 code4 原位升级验收、sw753dp／
  200% font expanded-layout、同机 disposable-user clean profile，并恢复
  基线；本机 env canonical 账号的登录／同步／登出／重启读回也已通过，
  Pulse 聚合进度 7→8 行。另一个 disposable user 中的 active byte 0
  故意损坏后，exact code4 已把 previous 恢复为原 SHA；UI、slot state 与
  scoped logs 通过，user/helper/签名测试产物均清理。production landing
  随后提升并完成普通流量读回。
- 本次 legacy closeout 由 owner 明确指定两台手机已安装的 byte-exact code3
  作为 code4 实体原位升级基线；这不把 code3 重分类为 public accepted
  release，也不得成为未来例外。下一版必须从当前 public accepted code4
  在选定门机原位升级。
  LE2120 只完成 code4 原位更新、登录、榜单与反馈；owner 已叫停后续操作，
  且其临时 Wi-Fi proxy 是否已人工恢复为 None 尚未确认。未经重新授权不得
  触碰该机；它不再是本 release 的必要门。
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
  :app:testPlayDebugUnitTest \
  :app:lintDirectDebug \
  :app:lintPlayDebug \
  :app:assembleDirectDebug \
  :app:assemblePlayDebug
```

发布必须执行 `docs/VERIFICATION_STANDARD.md` 全部八点与舰队 release card。

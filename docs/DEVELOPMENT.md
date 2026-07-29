# 开发指南

## 环境

| 组件 | 版本 | 本机位置 |
|---|---|---|
| JDK | **21**（用 17 会报 `invalid source release: 21`） | `/opt/homebrew/opt/openjdk@21` |
| Android SDK | compileSdk/targetSdk **37**，minSdk 23 | `/opt/homebrew/share/android-commandlinetools` |
| Gradle | 9.6.1（wrapper 自带） | — |
| Kotlin / AGP | 2.3.10 / 9.2.1 | — |
| Python | canonical venv ＋ `zhconv==1.4.3`（内容管线繁转简） | `/Users/ylsuen/.venv` |

```bash
echo "sdk.dir=/opt/homebrew/share/android-commandlinetools" > local.properties
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
/Users/ylsuen/.venv/bin/pip install -r content/requirements.txt
node scripts/bootstrap_public_content.mjs
```

公开 Git 不提交生成语料；`public-content-lock.json` 只记录已授权发布对象的
URL、hash、size 与 counts。bootstrap 会限制下载大小并验证这些字段后，原子
恢复 App/Worker 的 exact bytes。CI 使用同一流程。

## 构建

```bash
./gradlew :app:assembleDirectDebug     # 自助分发渠道（开启应用内自检更新）
./gradlew :app:assemblePlayDebug       # 商店渠道（关闭自检更新）
./gradlew :app:assembleDirectRelease   # 发布包，需签名环境变量
```

两个渠道（flavor）的区别只有 `SELF_UPDATE_ENABLED`：`direct` 包 id 带 `.direct` 后缀，
可与商店版共存，方便对照测试。

## 测试

```bash
./gradlew :app:testDirectDebugUnitTest
```

52 项单元测试，分九组：

- `ProgressionTest` —— 段位阶梯、掌握度评分、连续天数加成。
  含几条刻意写死的边界：**只读不练拿不到掌握**、**一题答对不足以判定掌握**、
  **集满第三星即晋段而非停在三星**。改这些数值时测试会红，是故意的。
- `ContentBundleTest` —— **直接解析 `app/src/main/assets/content.json`**，
  校验的是真正会装进 APK 的那份内容：512 章、二十篇章数、每章原文译文非空、
  别名可解析、215 题答案与诊断齐备、真题回挂章句、出题稳定性、界面用字为简体。
- `MarkdownStripTest` —— 锁定 AI 纯文本渲染的 Markdown 清理边界。
- `AppUpdateManifestTest` —— 锁定 Direct 精确包名、不可变 APK URL、hash、
  size 与发布时间契约。
- `ContentManifestTest` / `ContentDeltaTest` —— 锁定内容寻址路由和差量重建。
- `ContentReleaseFilesTest` —— 锁定 staged/active/previous 原子发布与恢复。
- `ProgressPayloadTest` —— 锁定 outbox schema、mutation id 与客户端来源字段。
- `FeedbackReceiptTest` —— 只有明确 stored、合法 UUID 与 Telegram 状态的响应
  才能显示为反馈回执。

内容出问题，`ContentBundleTest` 就该红。这是内容管线的回归网。

## 内容管线

```bash
/Users/ylsuen/.venv/bin/python content/build_content.py --check   # 只校验不写文件
/Users/ylsuen/.venv/bin/python content/build_content.py           # 写 content/dist/
```

改完内容后要把产物同步到两处：

```bash
cp content/dist/content.json  app/src/main/assets/content.json
cp content/dist/manifest.json app/src/main/assets/content-manifest.json
cp content/dist/{content,manifest}.json worker/public/
```

上面只用于尚未公开的候选内容。发布时必须先按 `docs/DEPLOYMENT.md` 上传新的
不可变 R2 对象、公开回读，并更新 lock 与 `content-releases.js`；不要用旧
lock 的 bootstrap 覆盖候选产物。

管线自带断言：章数必须 512、每篇章数必须与杨伯峻本吻合、题目答案必须在选项内、
每个错项必须有 why 诊断、概念人物引用必须能解析。任一不过直接非零退出。

## 模拟器

```bash
$ANDROID_HOME/emulator/emulator -avd lunyu-test -no-window -gpu swiftshader_indirect -no-audio &
adb wait-for-device
adb install -r app/build/outputs/apk/direct/debug/app-direct-debug.apk
adb shell am start -n net.bdfz.weibian.direct/net.bdfz.weibian.MainActivity
```

常用检查：

```bash
adb logcat -d -b crash                      # 崩溃缓冲，应为空
adb logcat -d | grep -i weibian             # 本应用日志
adb exec-out screencap -p > shot.png        # 截图
adb shell cmd uimode night yes              # 切深色模式
adb shell pm clear net.bdfz.weibian.direct  # 清空数据，测首启
```

**坑**：用 `nohup ... &` 起模拟器，进程组一结束就会被一起收掉。
要么用能跨调用存活的后台方式，要么 `exec` 起。

## 代码约定

- 注释写**为什么**，不写"这行干什么"。数值有依据就把依据写下来
  （段位阈值、掌握度权重、匹配阈值都注明了理由）。
- `domain/` 保持无 Android 依赖，方便直接单测。
- 所有写操作走 `LearningRepository`，不要让界面直接碰 DAO ——
  修为累计、当日统计、同步入队是绑在一起的，绕过去就会漏。
- 颜色**不要用 `primary` 表示"正确"**：本主题 primary 是朱砂，与 error 几乎同色，
  对错状态会长得一模一样（这是已经踩过的坑）。正确用 `secondary`（青瓷绿）。
- 长文本用一个 `Text` ＋ 点击落点反查，不要拆成多个 `Text` 塞进 `FlowRow` ——
  后者会让中文按"段"而不是按"字"折行，长章排版会碎掉。

# 贡献指南

## 开始之前

读 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)（环境与构建）与
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)（为什么这样分层）。

需要 **JDK 21**。没有签名密钥也能构建 —— 会产出未签名包。

## 提交前

```bash
/Users/ylsuen/.venv/bin/python content/build_content.py --check
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:assembleDirectDebug :app:testDirectDebugUnitTest
```

两条都必须绿。

## 改内容

**不要直接改 `app/src/main/assets/content.json`** —— 它是生成物。
改上游语料后重跑管线，并把产物同步到 assets 与 `worker/public/`。

内容改动必须能过 `ContentBundleTest`：512 章、二十篇章数、答案在选项内、
每个错项有 why 诊断、真题能回挂章句、界面用字为简体。

## 改题库

每道题的每个**错项都必须有 `why`**（为什么这个选项不对）。
没有诊断的错项等于只告诉学生"错了"，教不了任何东西 —— 构建期会直接拦下。

## 代码约定

- 注释写**为什么**，不写"这行干什么"；数值有依据就写下依据。
- `domain/` 保持无 Android 依赖。
- 写操作一律走 `LearningRepository`。
- 不要用 `primary`（朱砂）表示"正确"，用 `secondary`（青瓷）。
- 长文本用单个 `Text` ＋ 点击落点反查，别拆进 `FlowRow`。

## 不接受的改动

- 在本项目新建用户表、密码、注册或找回流程（身份属于 `my.bdfz.net`）
- 在 App 内内置模型密钥或绕过 `apis.bdfz.net`
- Room 用 `fallbackToDestructiveMigration`
- 写入上游语料仓库
- 引入任何第三方美术素材（见 [art/README.md](art/README.md)）

## 报问题

带上：设备与 Android 版本、App 版本与内容版本（"我 → 关于"里有）、复现步骤。
**不要**贴账号密码、Cookie 或他人学习内容。

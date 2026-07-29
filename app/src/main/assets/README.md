# 内容包放这里

`content.json` 与 `content-manifest.json` 是**生成物且不入库**
（语料含杨伯峻《论语译注》的译注，著作权归中华书局，公开仓库不得再分发）。

构建 App 前先生成：

```bash
python3 content/build_content.py
cp content/dist/content.json  app/src/main/assets/content.json
cp content/dist/manifest.json app/src/main/assets/content-manifest.json
```

管线从本机既有语料构建，来源见 `content/build_content.py` 头部说明。

# 内容包放这里

`content.json` 与 `content-manifest.json` 是**生成物且不入库**。公开发布的
授权范围与收据见 `docs/CONTENT_RIGHTS_RECEIPT.md`。

公开仓库/CI 从锁定的内容寻址对象恢复精确字节：

```bash
node scripts/bootstrap_public_content.mjs
```

本机内容 authority 变更时先从只读上游重建：

```bash
/Users/ylsuen/.venv/bin/python content/build_content.py
cp content/dist/content.json  app/src/main/assets/content.json
cp content/dist/manifest.json app/src/main/assets/content-manifest.json
```

随后更新 `content/public-content-lock.json`，上传新的不可变对象，并在
Worker `content-releases.js` 追加映射；没有逐字节 readback 不得移动清单。

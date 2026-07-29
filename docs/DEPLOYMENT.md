# 部署指南

## 一、内容接口 Worker

```bash
cd worker
cp ../content/dist/{content,manifest}.json public/
source ~/.secrets.env
export CLOUDFLARE_ACCOUNT_ID=da810f08b63347a01d3db7fd42619972
npx wrangler deploy
```

部署后自检：

```bash
curl -s https://weibian.bdfz.net/api/health | jq
curl -s https://weibian.bdfz.net/api/content/manifest | jq '{contentVersion,sha256,size,counts}'
# 校验下发内容与清单 sha256 一致
test "$(curl -s https://weibian.bdfz.net/api/content/bundle | shasum -a 256 | cut -d' ' -f1)" \
   = "$(curl -s https://weibian.bdfz.net/api/content/manifest | jq -r .sha256)" && echo SHA-OK
```

内容包作为 Worker 静态资源随部署发布，所以**内容版本 = 部署版本**，
回退用 `npx wrangler rollback`，不需要单独的内容回滚机制。

首次部署还需在 Cloudflare 为 `weibian.bdfz.net` 配置路由/自定义域。

### 上线前的注册事项（本机强制）

按 `runbooks/bdfz_project_matrix_and_interdependencies.md`，任何新公开站点必须
在同一次事务里登记到四个产品面 ＋ Pulse 监控面：

- [ ] 用户中心 `SITE_REGISTRY` 加 `weibian` 条目
- [ ] `bdfz-nav/sites.json`
- [ ] 门户 `suen/allinone/index.html#portalGroups`
- [ ] Companion `constants/sites.ts#SERVICES`（或显式标注 App-not-applicable）
- [ ] `pulse/src/sites.js`，并在 `/api/meta`、`/api/range` 实测到该 host

**学生数据分级**：`student_owned`（写入学习进度）。因此上线前必须有一次
真实登录 + 进度写入 + 回读验证，仅加载脚本不算集成。

---

## 二、APK 发布

### 签名

密钥与口令只从环境变量读，**绝不入库**：

```bash
export WEIBIAN_ANDROID_KEYSTORE_PATH=~/.android/weibian-release.keystore
export WEIBIAN_ANDROID_KEYSTORE_PASSWORD=...
export WEIBIAN_ANDROID_KEY_ALIAS=weibian
export WEIBIAN_ANDROID_KEY_PASSWORD=...
./gradlew :app:assembleDirectRelease
```

四个变量缺任意一个仍能出**未签名包**，方便 CI 与外部贡献者构建。

发布前必须核对（`runbooks/bdfz_android_app_update_standard.md` §5）：

```bash
# 签名指纹须与上一个已接受版本一致
apksigner verify --print-certs app-direct-release.apk | grep SHA-256
# versionCode 必须严格递增
aapt2 dump badging app-direct-release.apk | head -1
```

**坏版本的修法是发一个更高 versionCode 的修复版**，不要靠"回退到更低版本号"。

### 上传（顺序不可颠倒，fail-closed）

内容寻址、不可覆盖：

```
apps/weibian-android/releases/v<SEMVER>/<HASH8>/weibian-<SEMVER>.apk
apps/weibian-android/releases/v<SEMVER>/<HASH8>/release.json
apps/weibian-android/latest.apk        （可选便捷别名）
apps/weibian-android/latest.json       （最后才写）
```

1. 先传**已签名的内容寻址 APK**
2. 再传不可变的 release.json
3. 可选别名
4. **最后**才更新 `latest.json` 指针
5. 门户下载链接等公开读**逐字节回读通过**之后再放出

`latest.json` 必须符合 `bdfz-android-update-v1`：

```json
{
  "schema": "bdfz-android-update-v1",
  "appId": "net.bdfz.weibian",
  "version": "1.0.0",
  "versionCode": 1,
  "minAndroidApi": 23,
  "apkUrl": "https://img.bdfz.net/apps/weibian-android/releases/v1.0.0/<HASH8>/weibian-1.0.0.apk",
  "sha256": "<64 位小写十六进制>",
  "size": 20971520,
  "publishedAt": "2026-07-28T00:00:00Z",
  "releaseNotes": ["首个版本：512 章全本、215 道精编题、北京卷《论语》真题"],
  "mandatory": false
}
```

客户端会拒绝：schema 或包名不符、versionCode 非递增、
`apkUrl` 不以 `https://img.bdfz.net/apps/weibian-android/` 开头、
sha256 格式非法、size ≤ 0、清单体积超限。这些校验都在
`update/AppUpdateManager.kt` 里，改契约要两边一起改。

### GitHub Release

第二分发面，必须与 R2 **同一份字节**：

- 同样的 APK、同样的 sha256、同样的签名指纹
- 附 R2 不可变 URL
- 写明构建与安装方法
- 写明已知验证缺口（例如"真机未验收"）

---

## 三、回滚

| 故障 | 处理 |
|---|---|
| 内容包有错 | `cd worker && npx wrangler rollback` |
| `latest.json` 指错 | 把指针改回上一个已知良好版本 |
| 门户页面坏了 | 恢复上一个 Pages/Worker 部署 |
| 坏 APK 已被安装 | 发**更高 versionCode** 的修复版；不要引导用户卸载重装 |
| 哈希/体积对不上 | 立即撤下指针与门户链接，**保留证据**，不要覆盖内容寻址对象 |
| 签名不一致 | 停止发布。绝不把"卸载后装未知签名版"训练成常规操作 |

所有生产变更与验证证据记入 `reports/agent_action_log.jsonl` 与运维总报告，
不写任何密钥、Cookie、会话 id 或学生内容。

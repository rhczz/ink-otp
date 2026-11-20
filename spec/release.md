# Android 发布契约

每次发布使用一个 `v{版本}` 标签、一个已合入 `master` 的源码提交和一个 GitHub Release。标签须对应 Android `versionName`，并按语义化版本格式命名。`versionCode` 必须高于已有 Android 版本使用的构建号。带预发布后缀的版本在 GitHub 标记为预发布。

Android 安装包使用包名 `io.github.rhczz.inkotp` 和既有发布签名。覆盖升级依赖包名、签名和递增的 `versionCode`。已发布的历史标签和下载文件继续保留。

| Assets | 内容 |
| --- | --- |
| `InkOTP-{版本}.apk` | 固定发布证书签名的 Android 通用 APK |
| `SHA256SUMS` | 该 Release 里其他三个发布资产的 SHA-256 |
| `build-info.json` | 包版本、签名、源码提交与 GitHub Actions 运行等构建来源 |
| `release-notes.md` | 该版本的变更和验收范围 |

发布工作流从同一次验证运行取得未签名 APK、签名并复核发布材料。维护步骤见 [发布指南](../docs/releasing.md)。

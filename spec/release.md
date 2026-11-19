# 跨平台发布契约

每次公开发布使用一个 `v{版本}` 标签、一个已合入 `master` 的源码提交和一个 GitHub Release。Android、macOS 和 iOS 必须通过各自的构建与验收，发布任务才可公开完整的下载集。版本使用 `X.Y.Z`，可附加 `-beta.N` 等预发布后缀。带预发布后缀的版本在 GitHub 标记为预发布。

Android `versionName` 使用完整发布版本。macOS 和 iOS 使用相同的 `X.Y.Z` 作为 `CFBundleShortVersionString`，完整版本与预发布后缀通过标签、发布说明及 App 的版本显示呈现。Android `versionCode` 与 Apple `CFBundleVersion` 仍按各平台规则递增，用于安装和覆盖升级。macOS 取 Android `versionCode + 100`，从旧版 macOS 构建号 39 继续向前。iOS 在 Xcode 项目中单独递增构建号。历史标签和资产继续用于下载和升级验收。

| Assets | 内容 |
| --- | --- |
| `InkOTP-{版本}.apk` | Android 通用 APK，固定发布证书签名 |
| `InkOTP-{版本}-macOS-universal.dmg` | macOS 通用磁盘镜像，支持 Apple Silicon 和 Intel |
| `InkOTP-iOS-Simulator.zip` | iPhone/iPad 模拟器应用 |
| `InkOTP-iOS.ipa` 或 `InkOTP-iOS-unsigned.ipa` | iPhone/iPad 设备包，每个发布只提供其中一种 |
| `SHA256SUMS` | 该 Release 里其他发布资产的 SHA-256 |
| `build-info.json` 与 `release-notes.md` | 构建来源与版本说明 |

正式发布要求 macOS Developer ID 签名与公证，以及 iOS Apple 分发签名。Beta 可以发布临时签名的 DMG 或未签名的 IPA，并在发布说明中标明状态及安装限制。未签名 IPA 需使用 Apple 签名及覆盖目标设备的描述文件后才能安装。现有 Android 更新继续使用原签名。

工作流和维护步骤见 [发布指南](../docs/releasing.md)。

# InkOTP 版本发布

Android、macOS 和 iOS 共用一个 `v{版本}` 标签与一个 GitHub Release。合并发布版本到 `master` 后推送该提交的标签，例如 `v0.5.4-beta.1`。标签对应 Android `versionName`，Apple 两端用 `0.5.4` 作为系统包版本。构建号按平台的安装和覆盖升级规则递增。格式、资产命名及签名约束见 [发布契约](../spec/release.md)。

原先的 Android 版本、`macos-v*` 和 `ios-v*` 发布继续提供下载。新的公开发布使用 `v*` 标签，由统一的工作流创建。

## 工作流与配置

分支和 PR 执行质量检查。发布标签触发 [统一发布工作流](../.github/workflows/release.yaml)，复用 Android 与 iOS CI、跨平台读写检查和 macOS 双架构构建与打包检查。手动执行发布工作流用于检查，在标签推送时才签名并公开发布。`.github/ci.yaml` 和 `.github/release.yaml` 是 Python 门禁实际读取的配置。

验证通过后，Android 从同次运行取得唯一通用 APK 和 Gradle 元数据，使用固定证书签名。工作流以 `contents: read` 权限构建、测试和签名，签名 Secrets 只注入所需的步骤。Android 私钥临时写入 `runner.temp` 下的 `0600` 文件，步骤退出时清理。发布任务单独取得 `contents: write`，汇集同一次运行中已经检验的资产并复查来源、版本、签名状态及哈希。`gh release create --verify-tag` 将资产上传到草稿，上传完成后再公开 Release。

发布运行共用 `inkotp-release` 并发组，按 GitHub Actions 的 `queue: max` 排队。发布顺序取决于进入等待队列的时间。每次发布重新检查现有标签和 Release；出现重复版本、任何必需产物缺失或校验失败时终止。版本带有预发布后缀时，在 GitHub 标记为预发布。

## Android 固定签名与首次配置

现有本地 beta 的公开证书 SHA-256 指纹固定在 `.github/release.yaml`：

```text
f1e8211a3f245bed886a048f8088b16be229072dcf77b65981e6ca5dcf642589
```

这只是公开指纹，不能用于签名。为保持覆盖升级，GitHub 必须使用对应的既有私钥。不要创建新密钥来绕过缺少 Secrets 的错误，也不要把私钥、签名配置或口令加入仓库。

管理员经用户明确同意后，在 `rhczz/ink-otp` 的 Actions Secrets 配置以下四项。私钥上传是独立授权事项；实现工作流不表示已经授权或已经上传。本轮已取得用户明确授权，核对既有证书后，将四项 Secrets 配置至目标仓库并复核名称；未将秘密值写入任何公开文件或日志。后续仓库迁移仍需重新核对授权与配置。

| Secret | 内容 |
| --- | --- |
| `INKOTP_SIGNING_KEYSTORE_BASE64` | 既有 keystore 的单行 Base64，解码后不超过 1 MiB |
| `INKOTP_SIGNING_STORE_PASSWORD` | 既有 keystore 口令 |
| `INKOTP_SIGNING_KEY_ALIAS` | 对应固定证书的条目别名 |
| `INKOTP_SIGNING_KEY_PASSWORD` | 对应条目的密钥口令 |

不要把这些值粘贴到 Issue、PR、发布说明或 Actions 日志。修改公共证书指纹属于签名身份变更，必须单独设计升级迁移，不能当作日常发布配置更新。

## Android 发布检查

`tools/release_artifacts.py` 从源码、Gradle metadata 和 APK 读取版本并要求完全一致；tag 使用严格版本格式并通过参数数组传给子进程，不进入 shell 插值。checkout 必须正好是标签指向的提交。`versionCode` 至少为 3，并且高于其他 Android 与三端统一版本标签对应的构建号。若本次标签已有 Release（包括草稿），发布停止。远端 Release 找不到对应源码 tag、GitHub API 失败或历史数据无法解析时也停止。

APK 必须是 `io.github.rhczz.inkotp`、非 debuggable、minSdk 26、targetSdk 36，且权限都在配置白名单内。白名单只允许相机、生物识别、隐藏悬浮窗及 AndroidX 的私有接收器权限。网络、媒体读取、外部存储等权限都会阻止发布。产物要求小于 15 MiB，包含 arm64-v8a、armeabi-v7a、x86、x86_64；所有 ELF PT_LOAD 段支持 16 KiB 对齐。

Android Build Tools 36.0.0 先执行 `zipalign -P 16`，再使用 `apksigner` 签名，签后执行对齐检查及 `apksigner verify --min-sdk-version 26 --print-certs`。只接受唯一且指纹匹配的非调试签名。发布前复用 APK 体积、ABI 和 ELF 门禁。

## Apple 签名

macOS 正式发布要求 Developer ID 签名、公证和贴票。Beta 在没有 Apple 凭据时可用临时签名，安装限制见 [macOS 说明](../macos/README.md)。

iOS 正式发布要求启用 `INKOTP_IOS_RELEASE_SIGNED` 并配置 Apple 分发签名。Beta 在仓库未启用分发签名时提供未签名 IPA。签名开关、描述文件和真机安装要求见 [iOS 说明](../ios/README.md)。

## Release 资产与复核

同一版本页的 **Assets** 提供 Android APK、macOS 通用 DMG、iOS 模拟器 ZIP、iPhone/iPad IPA、`build-info.json`、`release-notes.md` 和 `SHA256SUMS`。`SHA256SUMS` 覆盖所有其他发布资产，Android 仍可用 `apksigner` 独立核对签名证书。发布页说明当前 macOS 和 iOS 包的签名状态。

对已发布版本，重新下载完整资产并核对哈希，确认每个平台的包可在同一页取得。上传中断可能留下未公开的草稿。遇到发布中断，从 Actions 运行、标签和资产状态定位，确认后再处理。现有 Release 的自动覆盖和重传由历史检查拒绝。

## 验证范围

本地可运行 `python3 -m unittest discover -s tools/tests -v` 验证发布工具，再用 `actionlint` 检查工作流。当前 actionlint 1.7.12 对 GitHub 已支持的 `concurrency.queue` 给出额外诊断，可精确忽略该字段的诊断。

发布通过需要本次 Actions 运行和远端资产复核。Android 和 iOS 真机安装、覆盖升级、取消、系统验证、相机和性能，以及 macOS 硬件环境与签名迁移，依实际验收结果记录。Beta 说明应列出尚待核实的范围。早期 Android 发布证据见 [首版交付记录](changes/2026-11-17-ci-and-release.md) 和 [0.4.1 发布记录](changes/2026-11-17-release-0.4.1.md)。

## 官方依据

- [GitHub 工作流复用与权限传递](https://docs.github.com/en/actions/how-tos/reuse-automations/reuse-workflows)
- [GitHub 并发排队与上限](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency)
- [gh release create 的 tag 验证和 prerelease 参数](https://cli.github.com/manual/gh_release_create)
- [Android zipalign 的 16 KiB 对齐与签名前顺序](https://developer.android.com/tools/zipalign)
- [Android apksigner 的签名、验签、口令环境变量参数](https://developer.android.com/tools/apksigner)
- [Apple 系统版本字段 `CFBundleShortVersionString`](https://developer.apple.com/documentation/bundleresources/information-property-list/cfbundleshortversionstring)

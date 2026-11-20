# InkOTP 版本发布

Android 使用 `v{版本}` 标签发布。版本合入 `master` 后，在对应提交上创建并推送标签。标签必须匹配语义化的 `versionName`，`versionCode` 须高于既有 Android 构建号。已有标签与下载资产继续保留。 [发布契约](../spec/release.md)

## 工作流与配置

分支和 PR 执行 Android 质量检查。发布标签触发 [发布工作流](../.github/workflows/release.yaml)，复用[同一套 Android CI](../.github/workflows/ci.yaml)。手动运行发布工作流时只执行 CI，推送版本标签后才签名和发布。质量与发布门禁分别读取 `.github/ci.yaml` 和 `.github/release.yaml`。

验证通过后，Android 从同次运行取得唯一通用 APK 和 Gradle 元数据，使用固定证书签名。工作流以 `contents: read` 权限构建、测试和签名，签名 Secrets 只注入所需的步骤。Android 私钥临时写入 `runner.temp` 下的 `0600` 文件，步骤退出时清理。发布任务单独取得 `contents: write`，下载同次运行的已签名产物并复查来源、版本、证书及哈希。`gh release create --verify-tag` 将资产上传到草稿，上传完成后再公开 Release。

发布运行共用 `inkotp-release` 并发组，按 GitHub Actions 的 `queue: max` 排队。发布顺序取决于进入等待队列的时间。每次发布重新检查现有标签和 Release；出现重复版本、任何必需产物缺失或校验失败时终止。版本带有预发布后缀时，在 GitHub 标记为预发布。

## Android 固定签名与首次配置

官方 Android 发行签名的公开证书 SHA-256 指纹固定在 `.github/release.yaml`。

```text
f1e8211a3f245bed886a048f8088b16be229072dcf77b65981e6ca5dcf642589
```

要保持官方 APK 的覆盖升级，GitHub Actions 使用与该公开指纹匹配的既有私钥。本机内测签名和保存位置见 [开发与贡献](development.md#本地签名内测包)。

管理员获得私钥上传授权后，在 `rhczz/ink-otp` 的 Actions Secrets 配置以下四项。首次配置的记录随首个发布归档。迁移仓库时重新核对授权、Secrets 和证书指纹。 [首次配置与发布记录](changes/2026-11-17-ci-and-release.md)

| Secret | 内容 |
| --- | --- |
| `INKOTP_SIGNING_KEYSTORE_BASE64` | 既有 keystore 的单行 Base64，解码后不超过 1 MiB |
| `INKOTP_SIGNING_STORE_PASSWORD` | 既有 keystore 口令 |
| `INKOTP_SIGNING_KEY_ALIAS` | 对应固定证书的条目别名 |
| `INKOTP_SIGNING_KEY_PASSWORD` | 对应条目的密钥口令 |

不要把这些值粘贴到 Issue、PR、发布说明或 Actions 日志。修改公共证书指纹属于签名身份变更，必须单独设计升级迁移，不能当作日常发布配置更新。

## Android 发布检查

`tools/release_artifacts.py` 从源码、Gradle metadata 和 APK 读取版本并要求完全一致；tag 使用严格版本格式并通过参数数组传给子进程，不进入 shell 插值。checkout 必须正好是标签指向的提交。`versionCode` 至少为 3，且高于全部历史 `v*` 标签中的 Android 构建号。历史平台专用标签用于核对旧发布记录。若本次标签已有 Release（包括草稿），发布停止。远端 Release 找不到对应源码 tag、GitHub API 失败或历史数据无法解析时也停止。

APK 必须是 `io.github.rhczz.inkotp`、非 debuggable、minSdk 26、targetSdk 36，且权限都在配置白名单内。白名单只允许相机、生物识别、隐藏悬浮窗及 AndroidX 的私有接收器权限。网络、媒体读取、外部存储等权限都会阻止发布。产物要求小于 15 MiB，包含 arm64-v8a、armeabi-v7a、x86、x86_64；所有 ELF PT_LOAD 段支持 16 KiB 对齐。

Android Build Tools 36.0.0 先执行 `zipalign -P 16`，再使用 `apksigner` 签名，签后执行对齐检查及 `apksigner verify --min-sdk-version 26 --print-certs`。只接受唯一且指纹匹配的非调试签名。发布前复用 APK 体积、ABI 和 ELF 门禁。

## Release 资产与复核

版本页的 **Assets** 提供签名 APK、`build-info.json`、`release-notes.md` 和 `SHA256SUMS`。`SHA256SUMS` 覆盖其余三个发布资产，APK 签名证书可用 `apksigner` 独立核对。

发布后，从版本页将资产全部下载到同一目录，执行 `shasum -a 256 -c SHA256SUMS`，并核对 APK 版本、签名证书及构建来源。上传中断可能留下未公开的草稿。遇到发布中断，从 Actions 运行、标签和资产状态定位，确认后再处理。现有 Release 的自动覆盖和重传由历史检查拒绝。

## 验证范围

本地可运行 `python3 -m unittest discover -s tools/tests -v` 验证发布工具，再用 `actionlint` 检查工作流。actionlint 1.7.12 会报告 `concurrency.queue` 未知。可忽略这一条诊断，其余检查照常执行。

发布通过需要本次 Actions 运行和远端资产复核。Android 真机安装、覆盖升级、取消、系统验证、相机和性能按实际验收结果记录。Beta 说明应列出尚待核实的范围。早期 Android 发布证据见 [首版交付记录](changes/2026-11-17-ci-and-release.md) 和 [0.4.1 发布记录](changes/2026-11-17-release-0.4.1.md)。

## 官方依据

- [GitHub 工作流复用与权限传递](https://docs.github.com/en/actions/how-tos/reuse-automations/reuse-workflows)
- [GitHub 并发排队与上限](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency)
- [gh release create 的 tag 验证和 prerelease 参数](https://cli.github.com/manual/gh_release_create)
- [Android zipalign 的 16 KiB 对齐与签名前顺序](https://developer.android.com/tools/zipalign)
- [Android apksigner 的签名、验签、口令环境变量参数](https://developer.android.com/tools/apksigner)

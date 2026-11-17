# InkOTP 版本发布

自动发布的入口是推送 `v{versionName}` tag。例如当前源码为 `versionName = "0.4.1-beta.1"`、`versionCode = 4`，只接受 `v0.4.1-beta.1`。普通分支推送和 PR 运行质量检查；手动执行 Release 工作流也只运行 CI，不签名、不创建 Release。0.4.1 手机反馈修复已完成本机交付，用户随后明确要求发布，执行与远端验证见 [发布记录](changes/2026-11-17-release-0.4.1.md)。

## 工作流与配置

GitHub 从 `.github/workflows/ci.yaml` 和 `.github/workflows/release.yaml` 加载工作流。`.github/ci.yaml`、`.github/release.yaml` 是由 Python 门禁实际读取的配置，采用 JSON 兼容 YAML，避免增加运行时 YAML 解析依赖。

tag 发布先调用同一份 CI；CI 不接触发布 Secrets，上传 `inkotp-unsigned-apk`，其中包含唯一通用 APK 与 Gradle `output-metadata.json`。签名 job 从同一个工作流运行下载这一产物，不重新构建，也不使用本机签名配置。CI 未成功时，签名和发布均不会运行。

签名 job 仅有 `contents: read`。四项签名 Secrets 只注入签名 step，私钥只写入 `runner.temp` 的 `0600` 文件；Python `finally` 和 shell `trap` 都负责清理。任何缺失、无效或不匹配都会终止；不会生成替代密钥。Runner 被强制销毁时无法保证进程清理代码执行，由 GitHub 临时 runner 生命周期清除磁盘。

发布 job 单独取得 `contents: write`，不接触签名 Secrets。它再次验签、核对文件与来源、查询 Release 历史，然后使用 runner 自带的 `gh release create --verify-tag` 创建新 Release；含预发布后缀的版本标记为 prerelease。不存在自动覆盖、删除或重传既有 Release 的流程。

所有 release 运行共用 `inkotp-release` 并发组，使用 GitHub 的 `queue: max` 排队，不取消已有运行。GitHub 当前队列上限为 100 个等待运行，超过上限会拒绝新运行；队列按进入等待的时间处理，不能依赖 tag 推送时间排序。每次发布都重新检查版本历史，因此低版本或重复版本仍会被拒绝。

## 固定签名与首次配置

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

## 发布前的判定

`tools/release_artifacts.py` 从源码、Gradle metadata 和 APK 读取版本并要求完全一致；tag 使用严格版本格式并通过参数数组传给子进程，不进入 shell 插值。checkout 必须正好是该 tag 指向的提交。`versionCode` 至少为 3，并且高于其他已有版本 tag；既有 Release（包括草稿）一律拒绝。远端 Release 找不到对应源码 tag、GitHub API 失败或历史数据无法解析时也停止。

APK 必须是 `io.github.rhczz.inkotp`、非 debuggable、minSdk 26、targetSdk 36，且权限都在配置白名单内。白名单只允许相机、生物识别及 AndroidX 的私有接收器权限。网络、媒体读取、外部存储等权限都会阻止发布。产物要求小于 15 MiB，包含 arm64-v8a、armeabi-v7a、x86、x86_64；所有 ELF PT_LOAD 段支持 16 KiB 对齐。

Android Build Tools 36.0.0 先执行 `zipalign -P 16`，再使用 `apksigner` 签名，签后执行对齐检查及 `apksigner verify --min-sdk-version 26 --print-certs`。只接受唯一且指纹匹配的非调试签名。发布前复用 APK 体积、ABI 和 ELF 门禁。

## 每个 Release 的文件

| 文件 | 用途 |
| --- | --- |
| `InkOTP-{versionName}.apk` | 可安装、固定签名的通用安装包 |
| `SHA256SUMS` | 另外三个发布文件的 SHA-256，下载后可复核 |
| `build-info.json` | tag、提交、运行编号、版本、证书、权限、ABI、APK 哈希及待真机验证范围 |
| `release-notes.md` | 来自 `docs/releases/{versionName}.md` 的版本说明 |

SHA256SUMS 与签名提供不同证据：哈希可检查下载完整性；固定 Android 签名是安装包身份和覆盖升级检查的依据。发布目录只能包含上表四个文件，意外文件、目录、符号链接和校验失败都阻止上传。

发布过程中若网络上传中断，GitHub 可能留下不完整 Release；重新运行会因“已存在 Release”而停止。需先人工核对资产、运行和签名，再决定修复方式；自动流程不会覆盖已有资产。

## 本地与真机验证边界

本地执行 `python3 -m unittest discover -s tools/tests -v` 可验证发布工具的成功判断及受控失败路径，不会访问真实签名私钥。工作流应另外运行 actionlint。当前 actionlint 1.7.12 尚不识别 GitHub 已正式支持的 `concurrency.queue`；只能针对这一条已核对官方文档的诊断做精确忽略，其他诊断仍必须解决。

本轮 `v0.4.0-beta.1` 已完成真实 tag 触发、复用 CI、签名、GitHub Release 发布，并下载资产通过同一 bundle 验证；证据见 [交付记录](changes/2026-11-17-ci-and-release.md)。后续版本仍须独立验证，不能仅凭本轮或本地测试宣称已发布，更不能写成 M3 真机验收完成。系统认证、相机、安装/覆盖升级/取消、不同 Android 版本与硬件、真实手机性能仍需用户安装 APK 后反馈。正式 beta 说明必须保留这些待测范围。

## 官方依据

- [GitHub 工作流复用与权限传递](https://docs.github.com/en/actions/how-tos/reuse-automations/reuse-workflows)
- [GitHub 并发排队与上限](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency)
- [gh release create 的 tag 验证和 prerelease 参数](https://cli.github.com/manual/gh_release_create)
- [Android zipalign 的 16 KiB 对齐与签名前顺序](https://developer.android.com/tools/zipalign)
- [Android apksigner 的签名、验签、口令环境变量参数](https://developer.android.com/tools/apksigner)

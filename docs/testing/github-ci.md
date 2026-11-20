# GitHub CI 质量门禁

分支推送和 PR 运行 Android 单测、构建、Lint、权限与独立格式对拍。发布标签调用同一份 CI 验证，使用通过检查的未签名 APK 继续签名与发布。 [CI](../../.github/workflows/ci.yaml) · [发布工作流](../../.github/workflows/release.yaml)

Android CI 以 `contents: read` 构建未签名的 Release APK。独立的发布工作流从同次运行取得产物并签名。Python 门禁 `tools/quality_gate.py` 读取 JSON 兼容的质量配置。 [发布指南](../releasing.md) · [质量配置](../../.github/ci.yaml)

## 什么会让检查失败

| 证据 | 拒绝条件 |
| --- | --- |
| core / App JUnit XML | 报告缺失、零测试、实际 testcase 少于 105 / 128、测试计数与报告不符、任何失败、错误或跳过 |
| core 全量 JaCoCo | 行覆盖率低于 90%，或分支低于 70%，或顶层计数缺失/为空 |
| App 全量 JaCoCo | 行覆盖率低于 75%，或分支低于 45%，或顶层计数缺失/为空 |
| debug / release Lint XML | 报告缺失或存在 Error / Fatal；Warning 数量保存在门禁 JSON |
| 源 Manifest / 合并后的 release Manifest | 应用可调试、出现权限白名单外的权限，或 camera / camera.any / camera.autofocus 未显式设为可选 |
| release APK 与 output-metadata.json | APK 不小于 15 MiB、缺 manifest / dex、不是四个目标 ABI、各 ABI 未带相同四个原生库、ELF PT_LOAD 对齐不足 16 KiB、元数据不对应唯一 APK |
| APK ZIP 对齐 | Android 官方 `zipalign -c -P 16 -v 4` 不通过；ELF 段对齐与 ZIP 页对齐分别验证 |
| 仓库文件 | Git 跟踪或未忽略的文件包含本地签名配置、私钥文件、APK/AAB、dist 文件或私钥正文 |

白名单允许 `CAMERA`、`HIDE_OVERLAY_WINDOWS`、`USE_BIOMETRIC`、`USE_FINGERPRINT`，以及 AndroidX 为包内广播接收器生成的 `io.github.rhczz.inkotp.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`。Debug 和 Release 的合并清单均检查离线权限。

JUnit 数量只防止整批测试被意外移除，覆盖率只防止测试范围退化；两者都不能证明功能绝对正确。App 报告包含系统适配和 UI 代码，不为抬高数字而排除难测文件。新增行为仍需验证成功、失败、取消、并发及升级路径。

## 本地如何复现

按 [开发环境说明](../development.md) 准备 JDK、SDK 和 Python，在仓库根目录执行 `make setup` 和 `make ci`。`make ci` 依次执行以下三个入口。

1. `make ci-gradle`：core 检查、App 单测/JaCoCo、宿主性能趋势、debug/release 构建以及两种 Lint。该入口要求没有 `android/local-signing.properties`，避免把本机签名产物误当成 CI 的未签名包。需要保留已有签名配置时可在单独的检出目录复现 CI。
2. `make ci-python` 运行 TOTP 参考自测、Google 迁移夹具复核、公共 OTP 和库向量检查、五种交换文件独立对拍、Python 单测，以及权限、存储和设计 token 检查。
3. `make ci-quality` 读取 XML、APK 和元数据，写入 `android/build/reports/quality-gate.json`，再用 Android Build Tools 检查 ZIP 对齐和 APK 签名状态。

只需检查已有证据时运行 `tools/.venv/bin/python tools/quality_gate.py`；缺失证据会返回非零，不会重新构建，也不会把旧报告替换为虚构结果。提交前还可运行 `tools/.venv/bin/python tools/quality_gate.py --hygiene-only`。仓库扫描不打印私钥内容；公开的 OTP 测试夹具可保留，不把测试密钥误判成真实用户数据。

`tools/tests/test_quality_gate.py` 用隔离的合成证据验证门禁。完整命令入口的基线通过后，分别删除报告、注入失败/跳过、降低覆盖率、缩小 APK 体积预算和制造 4 KiB ELF 对齐，断言退出码为 1。测试不修改正式 spec 或 fixture。

## 报告、产物与限制

Android 检查通过后上传 `inkotp-unsigned-apk`，包含 `app-release-unsigned.apk` 与 `output-metadata.json`，保留 7 天供同次发布流程签名。`verification-reports` 保留 JUnit、JaCoCo、Lint、宿主性能、格式对拍与门禁 JSON 14 天。Android 检查失败时也会尽量上传已经生成的报告。

Actions 按各自上游的提交 SHA 锁定。升级时核对上游提交和 Action 的输入。 [checkout v4.2.2](https://github.com/actions/checkout/releases/tag/v4.2.2)、[setup-java v4.7.1](https://github.com/actions/setup-java/releases/tag/v4.7.1)、[setup-python v5.6.0](https://github.com/actions/setup-python/releases/tag/v5.6.0)、[setup-android v3.2.2](https://github.com/android-actions/setup-android/releases/tag/v3.2.2)、[upload-artifact v4.6.2](https://github.com/actions/upload-artifact/releases/tag/v4.6.2)。

宿主性能 JSON 只记录 p50/p95 与分配趋势；确定性的缓存、ticker、锁定清理和解析预算由测试断言。CI 没有手机，不能据此声称相机、系统身份验证、文件选择器、前后台行为、卸载或安装升级已完成真机验收。真机结果包含安装和覆盖升级、迁移及使用到的系统能力。 [Android 设备测试](m4-phone-test.md) · [发布检查表](../release-checklist.md)

PR 的合并条件以仓库设置的必需状态检查为准。

# GitHub CI 质量门禁

GitHub 实际运行入口是 [`.github/workflows/ci.yaml`](../../.github/workflows/ci.yaml)。每次分支推送与 PR 都执行同一套检查；tag 发布通过 `workflow_call` 复用。它只申请 `contents: read`，不读取发布 Secrets，构建的 release APK 不带签名。安装版由发布工作流用固定身份签署。

用户约定的 [`.github/ci.yaml`](../../.github/ci.yaml) 是被 `tools/quality_gate.py` 实际读取的质量配置。它采用 JSON 兼容的 YAML，使用 Python 标准库解析，避免再引入配置依赖。不要把它移动到 workflows 目录，也不要把它当成独立的 GitHub 工作流。

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

权限白名单只有 CAMERA、USE_BIOMETRIC、USE_FINGERPRINT，以及 AndroidX 为包内广播接收器生成的 `io.github.rhczz.inkotp.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`。后者是应用专用 signature 权限，不授予网络或公共存储能力。debug 合并清单还单独运行离线与存储检查。

JUnit 数量只防止整批测试被意外移除，覆盖率只防止测试范围退化；两者都不能证明功能绝对正确。App 报告包含系统适配和 UI 代码，不为抬高数字而排除难测文件。新增行为仍需验证成功、失败、取消、并发及升级路径。

## 本地如何复现

JDK 25、Android `platforms;android-37.2`、`build-tools;36.0.0` 与 `ANDROID_HOME` 就绪后，执行 `make setup` 和 `make ci`。`ci` 按顺序运行以下三个入口，也可以逐项复现失败：

1. `make ci-gradle`：core 检查、App 单测/JaCoCo、宿主性能趋势、debug/release 构建以及两种 Lint。该入口要求没有 `android/local-signing.properties`，避免把本机签名产物误当成 CI 的未签名包；请将已有本机配置临时保留在仓库外，完成后恢复，不要删除签名身份。
2. `make ci-python`：现有 TOTP/备份参考自测、Kotlin/Python 独立对拍、第三方格式夹具与导出对拍、Python 单测、权限/存储/设计 token 检查。
3. `make ci-quality`：读取实际 XML/APK/元数据，写入 `android/build/reports/quality-gate.json`，再使用 Android build tools 检查 ZIP 对齐及未签名状态。

只需检查已有证据时运行 `tools/.venv/bin/python tools/quality_gate.py`；缺失证据会返回非零，不会重新构建，也不会把旧报告替换为虚构结果。提交前还可运行 `tools/.venv/bin/python tools/quality_gate.py --hygiene-only`。仓库扫描不打印私钥内容；公开的 OTP 测试夹具可保留，不把测试密钥误判成真实用户数据。

`tools/tests/test_quality_gate.py` 用隔离的合成证据验证门禁。完整命令入口的基线通过后，分别删除报告、注入失败/跳过、降低覆盖率、缩小 APK 体积预算和制造 4 KiB ELF 对齐，断言退出码为 1。测试不修改正式 spec 或 fixture。

## 报告、产物与限制

成功时上传 `inkotp-unsigned-apk`，其中只有 `app-release-unsigned.apk` 与 `output-metadata.json`，保留 7 天，供同次发布工作流使用。未签名包不能直接安装。失败时不会上传这个产物。`verification-reports` 用 `always()` 尽可能保留 JUnit、JaCoCo、Lint、宿主性能、格式对拍及门禁 JSON，保留 14 天；机器故障或强制终止仍可能导致报告不完整。

Actions 都锁定官方仓库提交 SHA。当前版本可从官方发布页核对：[checkout v4.2.2](https://github.com/actions/checkout/releases/tag/v4.2.2)、[setup-java v4.7.1](https://github.com/actions/setup-java/releases/tag/v4.7.1)、[setup-python v5.6.0](https://github.com/actions/setup-python/releases/tag/v5.6.0)、[setup-android v3.2.2](https://github.com/android-actions/setup-android/releases/tag/v3.2.2)、[upload-artifact v4.6.2](https://github.com/actions/upload-artifact/releases/tag/v4.6.2)。升级时同时检查官方提交与 Action 输入，不把浮动 tag 当成锁定。

宿主性能 JSON 只记录 p50/p95 与分配趋势；确定性的缓存、ticker、锁定清理和解析预算由测试断言。CI 没有手机，不能据此声称相机、系统身份验证、文件选择器、前后台行为、卸载或安装升级已完成真机验收。继续按 [M3](m3-phone-test.md) 和 [M4](m4-phone-test.md) 清单收集用户实测。

仓库 workflow 只能把不合格的 run 标红。要求 GitHub 阻止合并还需要维护者在仓库规则中将 `verify` 设为必需检查；未读取或修改远端规则时，不能宣称分支保护已经启用。

# InkOTP

> 完全离线的两步验证器，设计语言 **Quiet Ink**。
> Android、[iOS](ios/README.md) 和 [macOS](macos/README.md) 使用原生技术栈，并共用 [协议与测试合约](spec/conformance.md)。

## 下载安装

进入 [GitHub Releases](https://github.com/rhczz/ink-otp/releases)，在同一个版本的 **Assets** 里按平台选择 Android APK、macOS 通用 DMG 或 iOS 安装包。iOS 同时提供模拟器 ZIP，iPhone/iPad 安装要求见 [iOS 说明](ios/README.md)。

Android 支持 Android 8.0 及以上。已有用户建议先导出一份加密备份，再直接覆盖安装。历史版本的安装、恢复与反馈见 [Android 0.5 公测指南](docs/testing/0.5-public-beta.md)。

## 状态

- **M0 完成**：工程骨架、平台中立 spec、Python 参考实现、CI/Makefile。
- **M1 完成**：`:core` 纯 Kotlin 内核（Base32/OTP/URI/模型/JSON/AES-GCM/Argon2id/vault/backup/merge），81 个测试、行覆盖率 **93.4%**，Kotlin↔Python `.2fab` 双向对拍通过。
- **M2 完成**：Quiet Ink 设计系统（tokens/Inter 子集/Material Symbols）、15+ 组件、手写导航、10 个页面 + 首启引导/添加弹层/扫码占位；Roborazzi 42 张 light/dark/font200 渲染。
- **M3 内测实现**：已接入真实库、口令/设备密钥、生物识别、账号增删改与排序、真实出码、相机/图片扫码、SAF 备份、自动锁定及剪贴板保护。本机自动验证后交付 APK，由用户安装真机测试；当前不宣称 M3 真机验收完成。
- **M4 内测完善**：新增每次进入的系统身份验证、Aegis/2FAS/otpauth 双向交换、标准卸载与迁移配置检查、按周期缓存和按需资源诊断。结果见 [M4 记录](docs/changes/2026-11-17-m4-standards-auth-performance.md)，升级与试用见 [M4 手机说明](docs/testing/m4-phone-test.md)。真机性能与各厂商认证行为仍待验证。
- **0.4.1 手机反馈修复**：系统认证入口、全应用操作进度防闪屏、末五秒验证码颜色及相册权限核查，见 [修复记录](docs/changes/2026-11-17-phone-feedback-fixes.md)。
- **0.4.2 隐藏复制修复**：隐藏状态下点击复制始终保留圆点和隐藏读屏，认证规则保持既有设置，见 [记录](docs/changes/2026-11-17-hidden-copy-and-device-lock.md)。
- 每次修改与排查记录在 [docs/changes/](docs/changes/README.md)。本次实现与验证见 [M3 记录](docs/changes/2026-11-17-m3-local-beta.md)，手机安装和测试见 [内测说明](docs/testing/m3-phone-test.md)。

设计文档：[docs/design/2FA验证器-技术设计文档.md](docs/design/2FA验证器-技术设计文档.md)。

## 产品底线

- **不联网**：不申请 `INTERNET` 权限，不集成分析/崩溃 SDK。
- **加密存储**：整库 AES-256-GCM；DEK 由 Android Keystore 或恢复口令包装。
- **可恢复与互通**：默认 Aegis 加密备份，支持 2FAS/otpauth 文件及 Google Authenticator 迁移二维码双向交换；版本与信息损失以 [兼容矩阵](spec/interoperability.md) 为准。
- **可审计**：开源（GPL-3.0），依赖最小并锁定版本。

> 验证器只负责**生成**验证码，不负责校验；也无法在忘记恢复口令时帮你找回数据。

## 构建

需要：

- JDK **25**（Eclipse Temurin 25 LTS）
- Android SDK：`platforms/android-37.2`、`platform-tools`、`build-tools`（AGP 9.5 默认 36.0.0）
- 网络（仅首次下载 Gradle wrapper 与依赖；App 本身不联网）

```bash
make setup     # 创建 tools/.venv 并安装 Python 参考实现依赖
make test      # core/App 单测、覆盖率报告与脚本回归
make build     # :app:assembleDebug
make verify    # 单测 + 构建 + lint + 无网络权限检查 + 设计 token 扫描 + Python 自测
make screenshots  # Roborazzi 录制本机截图（不是像素比对门禁）
make performance  # 500/1000 条目本机趋势基准，不替代手机指标
```

也可以直接：

```bash
cd android
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
```

## 目录

```
docs/     设计与工程文档（唯一事实来源）
spec/     平台中立的数据格式、测试向量与用例
tools/    Python 参考实现与检查脚本（不随 App 发布）
android/  Gradle 工程（:core 纯 JVM，:app Compose）
macos/    SwiftPM 原生桌面应用、协议实现与 CI 打包脚本
ios/      SwiftUI 原生应用、Swift 核心和 Xcode 测试
```

## 安全

- 不要提交 keystore、备份文件、真实 secret。
- 漏洞请按 [SECURITY.md](SECURITY.md) 私下披露，不要开公开 issue。

## 许可证

[GPL-3.0](LICENSE)。第三方依赖与字体/图标的许可证见 `docs/` 与 App 内“关于与开源许可”。


## 本地内测安装包

运行 `python3 tools/build_local_beta.py` 会首次生成被 Git 忽略的本地签名材料，并构建已签名 Release APK。以后重复运行会使用同一私钥，支持覆盖升级。签名材料保留在 `android/.local-signing/` 与 `android/local-signing.properties`，不提交、不随 APK 分发；不能随意删除或替换。GitHub tag 发布使用同一签名身份，流程和 Secrets 规则见 [发布指南](docs/releasing.md)。

输出为 `android/app/build/outputs/apk/release/app-release.apk`。Release 开启屏幕保护，Debug 仍用于截图与开发。内测包不申请联网权限，不需要电脑与手机保持连接。

## GitHub 检查与发布

分支推送和 PR 执行 CI。`v{版本}` 标签触发三端的验证、打包和统一发布，同时提供 SHA-256 校验和及构建来源。本地用 `make ci` 复现 Android 无签名 CI。具体门禁和下载文件见 [CI 指南](docs/testing/github-ci.md) 和 [发布指南](docs/releasing.md)。

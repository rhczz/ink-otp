# 开发与贡献

## 代码入口

| 目录 | 内容 |
| --- | --- |
| `android/` | Gradle 工程。`:core` 是纯 Kotlin/JVM 核心，`:app` 是 Compose 应用 |
| `ios/` | SwiftUI 应用、Swift 核心和 XCTest |
| `macos/` | SwiftPM 桌面应用、密码学桥接和 DMG 打包脚本 |
| `apple/GoogleOTPTransfer/` | Apple 两端共用的 Google Authenticator 迁移编解码器 |
| `spec/`、`tools/` | 公共数据契约、测试向量、独立 Python 参考实现和检查脚本 |
| `website/`、`docs/` | 官网源码、设计文档和变更记录 |

项目的设计约束、协议和数据格式分别记录在以下文档。 [技术设计文档](design/2FA验证器-技术设计文档.md) · [公共规格](../spec/README.md)

## 本地开发 Android

准备 Make、JDK 25、Python 3.13（与 CI 一致）和 Android SDK。设置 `JAVA_HOME` 与 `ANDROID_HOME`，安装 `platform-tools`、`platforms;android-37.2`、`build-tools;36.0.0` 并接受 SDK 许可。Gradle Wrapper 和依赖需要从各自的仓库下载。版本锁定见 [工具链](toolchain.md)。

在仓库根目录依次运行这两个命令。

```bash
make setup
make verify
```

`make setup` 将 Python 依赖安装到 `tools/.venv`。`make verify` 运行核心和 App 单测、覆盖率检查、独立格式对拍、Debug 构建与 Lint，并核对权限、存储配置和设计 token。Debug APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`，包名为 `io.github.rhczz.inkotp.debug`，可与正式版并存。

| 命令 | 用途 |
| --- | --- |
| `make test` | 核心和 App 单测、覆盖率报告、Python 回归测试 |
| `make build` | 构建 Debug APK |
| `make screenshots` | 在本机录制 Roborazzi 截图 |
| `make performance` | 记录 500 和 1000 条目时的宿主性能趋势 |
| `make ci` | 复现 Android CI，含未签名 Release 构建、两种 Lint 及 APK 和报告门禁 |

只跑 Android 核心测试时，从根目录执行 `android/gradlew -p android :core:test`。复现 CI 时，在没有 `android/local-signing.properties` 的检出目录中运行 `make ci`。iOS 的构建与测试由 GitHub Actions 执行。macOS 工作流负责双架构构建、互读测试和 DMG 验收。参见 [CI 指南](testing/github-ci.md)、[iOS](../ios/README.md) 和 [macOS](../macos/README.md)。

## 本地签名内测包

`make beta` 调用 `tools/build_local_beta.py`，生成 `android/app/build/outputs/apk/release/app-release.apk`。脚本首次创建本地签名材料，后续构建复用 `android/.local-signing/` 和 `android/local-signing.properties`。请保管这两处文件，覆盖安装依赖原签名。签名材料和 APK 均保存在本地。正式发布的签名、共享标签和校验流程见 [发布指南](releasing.md)。

## 提交变更

- 从最新的远端 `master` 新建工作分支，按改动使用 `feature/`、`test/`、`chore/` 等前缀，通过 PR 合入 `master`。
- 功能、交互或数据格式改变时先更新设计和规格。测试使用公开向量或合成数据，按生成流程更新夹具。界面沿用 Quiet Ink tokens。
- 在 [变更记录](changes/README.md) 补齐缘由、范围、验收方式和实际结果，并更新索引。记录相关的成功、失败、取消、并发和升级路径，以及覆盖率和待验收设备。
- PR 说明受影响的平台、复现或验证步骤和已知限制。公开反馈请去除真实 OTP 密钥、验证码、恢复口令、签名私钥和个人备份。漏洞使用私密渠道报告。 [安全政策](../SECURITY.md) · [项目协作规则](../AGENTS.md)

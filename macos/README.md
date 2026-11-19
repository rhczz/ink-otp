# InkOTP for macOS

原生 SwiftUI 桌面应用，支持 macOS 14 及以上。SwiftPM 区分平台中立内核、密码学桥接和 macOS 应用。备份兼容范围见 [交换规格](../spec/interoperability.md)，桌面行为见 [macOS 规格](../spec/macos-parity.md)。

## CI 与分发

- [macOS 工作流](../.github/workflows/macos.yaml) 在 GitHub 的 Apple Silicon 与 Intel 运行器执行公共协议测试、生成覆盖率报告、进行独立 Python 对拍与编译。先在两种架构分别验证，再合并并校验通用 DMG。
- 标记合入 `master` 的提交为 `macos-v0.1.0-beta.1` 会触发发布。工作流创建预发布和可检验的 SHA-256 文件，保留仓库已有 Android 最新版本。分发包为 DMG，应用拖入 Applications 后运行。
- Beta 在没有 Apple 凭据时使用临时签名。Gatekeeper 可能拦截首次打开，需在验证下载校验和后按 Apple 系统提供的方式授权开启。系统认证依赖受保护的 Keychain，只有带授权配置的签名版本才能提供完整模式。
- 正式版本要求 Developer ID 签名、公证和贴票，接受已有仓库环境中的 `INKOTP_MACOS_*` 凭据。需要 `DEVELOPER_ID`、`P12_BASE64`、`P12_PASSWORD`、`PROFILE_BASE64`、`NOTARY_APPLE_ID`、`TEAM_ID` 和 `NOTARY_PASSWORD`。Developer ID 配置描述文件须匹配 `io.github.rhczz.inkotp.macos` 并允许该 App 的私有 Keychain 组。测试分支和拉取请求始终使用临时签名。
- OpenSSL 3.5 LTS 与 Argon2 源包在运行器上按固定 SHA-256 校验并静态链接。应用只依赖系统框架，不下载运行时组件。第三方许可证包含于应用包。

本次本地开发只涉及源码。Apple 工具链、编译、所有自动测试和发布在 GitHub Actions 执行。

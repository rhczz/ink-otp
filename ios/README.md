# InkOTP for iOS

InkOTP 使用 SwiftUI 和 Apple 系统框架，最低支持 iOS / iPadOS 17。应用层包含界面、会话、加密仓储和平台服务。Swift Package 单独实现 OTP、解析、库和交换格式。Android 与 iOS 共用 [格式与测试契约](../spec/README.md)。

- `Core/` 包含 Swift 核心和两种 Apple 框架未提供的离线 KDF。源代码、许可证和校验和值见 [原始来源](Core/Sources/CInkKDF/PROVENANCE.md)。
- `App/` 包含 SwiftUI、Keychain、LocalAuthentication、AVFoundation、PhotosUI、Vision 和 Files 集成。
- `AppTests/` 验证仓储和权限状态。 `UITests/` 用 XCUITest 驱动实际界面。

## GitHub Actions

[CI](../.github/workflows/ci.yaml) 运行 Android 既有检查、iOS XCTest、双向互读和独立 Python 核对。iOS 项目由 Actions 从 `project.yml` 生成。每次运行保留 XCTest 结果、覆盖率及构建产物。

[统一发布工作流](../.github/workflows/release.yaml) 从已合入 `master` 的 `v{版本}` 标签发布。在 [GitHub Releases](https://github.com/rhczz/ink-otp/releases) 的版本页 **Assets** 下载 `InkOTP-iOS-Simulator.zip`，用同页的 `SHA256SUMS` 核对后解压，并在 Xcode 模拟器安装其中的 `InkOTP.app`。

真机 IPA 使用 Apple Ad Hoc 分发签名，安装资格由 provisioning profile 的设备名单决定。启用签名时将 GitHub Actions 变量 `INKOTP_IOS_RELEASE_SIGNED` 设为 `true`，并在仓库 Secrets 配置 `INKOTP_IOS_CERTIFICATE_BASE64`、`INKOTP_IOS_CERTIFICATE_PASSWORD` 和 `INKOTP_IOS_PROFILE_BASE64`。证书对应 Apple Distribution，profile 对应 `io.github.rhczz.inkotp`。已签名的 IPA 和校验和由 Actions 生成。App Store 和 TestFlight 走 Apple 的分发流程。

Beta 发布未启用分发签名时，同页提供 `InkOTP-iOS-unsigned.ipa`。它使用 iPhoneOS SDK 为 iPhone 和 iPad 构建。安装到设备前需要使用自己的 Apple 签名身份及相应的描述文件签名。启用仓库分发签名时，发布页提供已签名的 `InkOTP-iOS.ipa`。历史预览版仍可从 [旧版 iOS 发布页](https://github.com/rhczz/ink-otp/releases/tag/ios-v0.5.3-preview.2) 下载。

## 真机验收

在最低支持版本和当前 iOS 版本上核对相机、照片二维码、Face ID、Touch ID、设备密码回退、生物识别配置变动后的重新绑定、后台和录屏遮挡、剪贴板到期、Files 提供商的导入和导出、安装覆盖升级以及卸载后重新安装。还需用 Aegis 和 2FAS 实际导入导出，与测试夹具覆盖的格式对应。测试结果记录到本次变更。

启用公开 GitHub Release 的 Ad Hoc IPA 签名时，嵌入的 profile 会随 IPA 公开，包括其中登记设备的标识。此配置适用于明确允许公开的测试设备。普通真机用户通过 Apple 的分发渠道安装。

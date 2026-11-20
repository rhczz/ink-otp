# InkOTP for iOS

InkOTP 是原生离线验证器，支持 iOS 和 iPadOS 17 及以上。可扫描服务提供的设置二维码，或手动输入设置密钥。验证码库保存在本机，并可使用 Face ID、Touch ID 或设备密码验证每次进入。

## 下载与安装

在 [GitHub Releases](https://github.com/rhczz/ink-otp/releases) 的同一版本 **Assets** 中选择 iOS 下载件，并使用同页的 `SHA256SUMS` 核对文件。

| 文件 | 用途 |
| --- | --- |
| `InkOTP-iOS-Simulator.zip` | 解压后在 Xcode 模拟器中安装 `InkOTP.app`，支持 Apple Silicon 和 Intel。 |
| `InkOTP-iOS-unsigned.ipa` | 未签名的 iPhone/iPad 归档。安装前需使用自己的 Apple 签名身份和覆盖目标设备的描述文件签名。 |
| `InkOTP-iOS.ipa` | 仓库启用 Ad Hoc 分发签名时提供，仅能安装到描述文件登记的设备。 |

## 备份与迁移

- 在「设置 → 备份与恢复」选择导出或导入备份。推荐 Aegis 加密备份，也支持 2FAS 和逐行 `otpauth://` 文本。导入时先核对账号，再合并或确认替换。若持有 0.6 之前生成的 `.2fab`，可在升级前用 [历史版](https://github.com/rhczz/ink-otp/releases/tag/v0.5.4-beta.1) 打开并另存为 Aegis 加密备份。已经升级的用户可在另一台设备或独立测试环境转换文件。
- 从 Google Authenticator 迁入时，先在 InkOTP 设置恢复口令，再通过「添加账号」扫码、从相册读取图片，或把迁移链接粘贴到手动输入框。收齐同一批的每张二维码后，核对一次再合并。
- 迁出时，在「设置 → 备份与恢复 → 迁移到 Google Authenticator」验证当前恢复口令并确认明文密钥提示，再在目标设备的 Google Authenticator 中逐页扫描。可迁出的 TOTP 使用 SHA1、6 位、30 秒。遇到不兼容的账号，可核对后选择只迁移兼容项。HOTP 参数和计数器可保留并再次导出，生成验证码需要支持 HOTP 的验证器。[迁移规格](../spec/google-authenticator-transfer.md)

## 构建与验收

GitHub Actions 通过 XcodeGen 从 `project.yml` 生成项目，运行 Swift 核心测试、iPhone XCTest、iPad 模拟器覆盖升级，以及 Android 与 iOS 的五种交换文件互读。`App/` 包含界面、会话和系统集成，`Core/` 包含数据契约、OTP 与离线 KDF。[开发指南](../docs/development.md) · [iOS 规格](../spec/ios-parity.md) · [KDF 来源和许可](Core/Sources/CInkKDF/PROVENANCE.md)

正式发布使用 Apple Distribution 证书及匹配 `io.github.rhczz.inkotp` 的 Ad Hoc 描述文件。配置 `INKOTP_IOS_RELEASE_SIGNED=true`，并设置 `INKOTP_IOS_CERTIFICATE_BASE64`、`INKOTP_IOS_CERTIFICATE_PASSWORD` 和 `INKOTP_IOS_PROFILE_BASE64`。公开 IPA 会携带描述文件及其中登记的设备标识，应使用允许公开的测试设备。[发布指南](../docs/releasing.md)

0.6.0-beta.1 已通过 iPhone XCTest 和 iPad 模拟器覆盖升级。iPhone/iPad 真机仍需验收覆盖安装、系统认证、相机、Files 文件提供商、后台与录屏遮挡、减少动态效果和 Google Authenticator 双向互扫。[本版验证记录](../docs/changes/2026-11-20-transfer-and-motion.md)

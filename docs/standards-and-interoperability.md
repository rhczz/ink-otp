# 标准机制与生态兼容

## 验证码与迁移格式

| 用途 | InkOTP 使用的约定 | 相关说明 |
| --- | --- | --- |
| 出码 | RFC 6238 定义 TOTP，复用 RFC 4226 的 HOTP 算法。 | [TOTP 原理](totp.md) |
| 添加单个账号 | 读取验证器生态常用的 `otpauth://` 设置链接，密钥以 RFC 4648 Base32 编码。 | [URI 参数与校验](../spec/README.md) |
| 文件备份与交换 | 支持 Aegis JSON、2FAS 和每行一个 `otpauth://` 的文本。加密备份默认写为 Aegis JSON。 | [格式、版本与字段兼容矩阵](../spec/interoperability.md) |
| Google Authenticator | 通过相机、图片或粘贴链接收集同批迁移二维码。迁出时显示目标设备逐页扫描的二维码。 | [迁移规格与不兼容参数](../spec/google-authenticator-transfer.md) |

加密文件和明文文件各有明确选项。导出明文文件或迁移码前需要额外确认。导入遇到不支持的 OTP 类型或参数时给出提示，账号备注、图标等附加资料的保留范围按各格式说明。旧版 InkOTP `.2fab` 从 0.6 起移除。

## 系统认证与文件选择

- Android 使用系统 BiometricPrompt 或 Keyguard。Android 11+ 使用强生物识别或锁屏凭据，Android 8–10 使用系统锁屏凭据确认。文件通过 Storage Access Framework 的系统选择器打开与保存。 [Android 生物识别](https://developer.android.com/identity/sign-in/biometric-auth) · [系统文件选择器](https://developer.android.com/training/data-storage/shared/documents-files)
- iOS 通过 LocalAuthentication 验证身份，用系统文件选择器打开和导出备份。导出时应用先在私有临时目录生成受文件保护的文件，供选择器转交。 [iOS 规格](../spec/ios-parity.md)
- macOS 使用 LocalAuthentication、Keychain 和系统打开、保存面板。受保护钥匙串的可用性与签名方式有关。 [macOS 规格](../spec/macos-parity.md)

设备锁屏凭据只在系统界面输入。应用恢复口令用于本机库恢复和备份加密。在 Android 上，开启每次进入验证后，文件选择器的暂时离开宽限为 60 秒，系统认证宽限为 120 秒。重复生命周期回调不会延长它们。 [Android 规格](design/2FA验证器-技术设计文档.md)

## 文件归属

应用将本机数据、设备包装和缓存保留在应用私有位置或系统密钥存储。用户主动保存的备份、已存在的相册二维码和外部副本由用户管理。卸载应用后，应按所选位置的规则管理这些文件。

Android 在 Manifest 和系统备份规则中排除应用数据。普通卸载、应用归档与系统保留数据属于不同流程。项目通过 `tools/check_android_storage.py` 核对配置，系统实际行为以真机检查为准。 [Android 应用专属存储](https://developer.android.com/training/data-storage/app-specific) · [Android 自动备份规则](https://developer.android.com/identity/data/autobackup)

新增格式时需要记录来源、版本、字段、拒绝条件和独立样本。加密格式还要验证认证失败和资源上限。 [验证入口](testing/github-ci.md)

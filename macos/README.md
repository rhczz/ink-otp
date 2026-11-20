# InkOTP for macOS

InkOTP 是原生离线验证器，支持 macOS 14 及以上的 Apple Silicon 和 Intel Mac。加密库保存在应用沙箱内，可用恢复口令重新绑定本机钥匙串。

## 下载与升级

在 [GitHub Releases](https://github.com/rhczz/ink-otp/releases) 的同一版本 **Assets** 中下载 `InkOTP-{版本}-macOS-universal.dmg` 和 `SHA256SUMS`。校验后打开 DMG，将 InkOTP 拖到「应用程序」并从那里启动。

Beta 可采用临时签名。首次打开可能需要通过 macOS 的安全界面授权。临时签名版的受保护钥匙串入口无法使用。设置恢复口令并保留一份加密备份，便于将来迁移到新的签名身份。

覆盖升级前，先在旧版导出一份加密备份。退出旧版后替换应用。自动解锁失败时，点「解锁验证码库」并按钥匙串提示操作。也可用原恢复口令重新绑定设备凭据。

## 备份与迁移

- 在「设置 → 备份」导出文件或导入备份。推荐 Aegis 加密备份，也支持 2FAS 和逐行 `otpauth://` 文本。导入时先核对账号，再合并或确认替换。若持有 0.6 之前生成的 `.2fab`，可在升级前用 [历史版](https://github.com/rhczz/ink-otp/releases/tag/v0.5.4-beta.1) 打开并另存为 Aegis 加密备份。已经升级的用户可在另一台设备或独立测试环境转换文件。
- 从 Google Authenticator 迁入时，在「添加账号」选择「从 Google Authenticator 导入…」，使用相机、二维码图片或粘贴链接。收齐同一批的每张二维码后，核对一次再合并。
- 迁出时，在「设置 → 备份 → Google Authenticator」选择「导出迁移二维码…」，核对兼容项、确认明文密钥提示并输入当前恢复口令，再在目标设备逐页扫描。可迁出的 TOTP 使用 SHA1、6 位、30 秒。HOTP 参数和计数器可保留并再次导出，生成验证码需要支持 HOTP 的验证器。[迁移规格](../spec/google-authenticator-transfer.md)

## 构建与验收

[macOS 工作流](../.github/workflows/macos.yaml) 在 GitHub 的 Apple Silicon 和 Intel 运行器执行公共协议测试、覆盖率、独立 Python 对拍、Android 与 macOS 的五种交换文件互读，并打包和验证通用 DMG。OpenSSL 与 Argon2 源码按固定 SHA-256 校验后静态链接。[开发指南](../docs/development.md) · [macOS 规格](../spec/macos-parity.md)

正式发布要求 Developer ID 签名、公证和贴票。环境中的 `INKOTP_MACOS_*` 凭据包括 `DEVELOPER_ID`、`P12_BASE64`、`P12_PASSWORD`、`PROFILE_BASE64`、`NOTARY_APPLE_ID`、`TEAM_ID` 和 `NOTARY_PASSWORD`。描述文件须匹配 `io.github.rhczz.inkotp.macos` 并授权应用的私有钥匙串组。[发布指南](../docs/releasing.md)

0.6.0-beta.1 的 9 项安装包 XCTest 已通过，包括 Google 多页样本导入、取消、替换后重启。仍需用实际 Google Authenticator 双向互扫，并在实体 Mac 上核对签名迁移、系统认证、相机、权限及辅助功能。[本版验证记录](../docs/changes/2026-11-20-transfer-and-motion.md)

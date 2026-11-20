# 跨平台数据契约

Android、iOS 和 macOS 使用 [公共规格](../spec/README.md) 中的 OTP 向量、设置链接示例和交换夹具。各平台用自己的系统接口处理存储、认证、剪贴板和文件选择。

## 本地库与可移植备份

- [本地加密库](../spec/vault-format.md)的 `2FAV` 字节布局和测试样本为三端共用。Android 和 macOS 写入私有 `vault.bin`，iOS 将 `2FAV` 负载封装在私有 `vault-v1.json` 中。设备密钥和认证状态由各平台管理。
- 换机与跨平台导入使用 [Aegis、2FAS 或 otpauth 交换格式](../spec/interoperability.md)，也可使用 [Google Authenticator 迁移二维码](../spec/google-authenticator-transfer.md)。导入在目标设备重新加密，兼容矩阵列出各格式保留的字段与参数范围。

## 实现规则

1. 按各自规格编码字段、枚举和整数范围。库模型和 `2FAV` 头只使用规范定义的字节和字段，系统对象、资源 ID 和平台错误留在接口层。
2. `2FAV` 的整数按大端序读写。库 JSON 使用 UTF-8，显示名称按契约规范化，密钥按格式要求编码。
3. Android `:core` 只依赖 JVM 接口，系统能力由 App 层提供。iOS 和 macOS 共用 `apple/GoogleOTPTransfer` 解析及生成迁移二维码内容。
4. 库布局或语义发生不兼容变化时，更新对应版本、规格和迁移演练，补齐独立实现及既有样本的测试证据。

## 怎样验证

运行 `make setup` 和 `make crosscheck`，用独立 Python 实现验证 OTP、URI、本地库示例和 Kotlin 实际导出的交换文件。[Android 与 iOS CI](../.github/workflows/ci.yaml)和 [macOS CI](../.github/workflows/macos.yaml)分别验证两组双向交换。三端都使用独立生成的 Google 迁移样本。真机和系统安装范围按对应版本的[变更记录](changes/README.md)核对。

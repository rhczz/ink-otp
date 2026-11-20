# 本地库与可移植格式

Android 使用 [公共规格](../spec/README.md) 中的 OTP 向量、设置链接示例和交换夹具。与其他验证器交换账号时，按来源和目标格式处理数据。

## 本地库与可移植备份

- [本地加密库](../spec/vault-format.md)使用 `2FAV` 字节布局，保存在应用私有的 `vault.bin` 中。设备包装由 Android Keystore 和应用私有 DataStore 管理。
- 换机和验证器之间的导入可使用 [Aegis、2FAS 或 otpauth 文件](../spec/interoperability.md)及 [Google Authenticator 迁移二维码](../spec/google-authenticator-transfer.md)。导入后在目标设备重新加密，兼容矩阵列出各格式保留的字段与参数范围。

## 实现规则

1. 按规格处理字段、枚举和整数范围。库 JSON 与 `2FAV` 头只包含规范定义的字段，系统对象、资源 ID 和平台错误由 Android 适配层处理。
2. `2FAV` 的整数按大端序读写。库 JSON 使用 UTF-8，显示名称和密钥按相应格式规范化及编码。
3. Android `:core` 只依赖 JVM 接口，系统能力由 `:app` 提供。
4. 库布局或语义发生不兼容变化时，更新对应版本、规格和迁移演练，补齐独立实现及既有样本的测试证据。

运行 `make setup` 和 `make crosscheck`，用独立 Python 实现核对 OTP、URI、本地库示例和 Kotlin 实际导出的交换文件。参见 [公共验收](../spec/conformance.md)。

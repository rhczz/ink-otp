# 公共协议测试集

Android 核心使用以下公共用例验证生产格式。测试账号和口令来自公开样本。独立 Python 实现核对夹具来源和写出结果。

| 输入 | 必须校验的行为 |
| --- | --- |
| `vectors.json` | RFC 向量、固定随机 TOTP、Base32 正反向、非规范输入、算法及数字边界 |
| `otpauth-cases.json` | 全部成功字段、迁移路由、警告与拒绝码 |
| `vault-format.md` 的二进制示例 | 本地库字节布局、认证头部和口令包装 |
| `fixtures/interoperability/sources.json` 引用的十个来源文件 | 内容识别、支持版本、口令失败、整份拒绝及不支持条目的原始序号 |
| `conformance-cases.json` | 来源文件的预期结果 |
| `fixtures/google-authenticator-v1.json` | 独立生成的 Google 迁移二维码数据、账号字段、分批顺序与无效输入 |
| Android 生成的五种交换文件 | 独立 Python 解密与字段比对，覆盖 SHA-256、8 位、60 秒周期和 HOTP 计数器 |

## 产物与对拍

Gradle `:core:test` 从仓库根目录的 `spec/` 读取样本，输出 `android/core/build/interoperability/expected.json` 和五份 `kotlin-<FORMAT>.<extension>`。预期库模型由 `backup-v1.expected.json` 的两个账号和 `Public Counter`／`sample` HOTP 账号组成，HOTP 使用 RFC 4226 的公开测试密钥，计数器为 10300。

`make crosscheck` 先确认 Android 的导出输入与公共库预期一致，再用独立实现验证每份文件中的账号顺序和验证码参数。它也核对第三方公开文件的 Git blob 哈希。Android 核心测试覆盖版本、错误口令及损坏数据。系统认证、相机和实际安装由 [Android 验收](../docs/release-checklist.md) 单独记录。

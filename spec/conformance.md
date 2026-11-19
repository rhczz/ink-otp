# 公共协议测试集

同一组用例由各平台适配器执行。测试资料中的账号和口令均来自公开测试样本。应用内核负责读取生产格式，Python 参考实现独立校验样本来源和各端写出结果。

| 输入 | 必须校验的行为 |
|---|---|
| `vectors.json` | 全部 RFC 向量、固定随机 TOTP、Base32 正反向、非规范输入、算法及数字边界 |
| `otpauth-cases.json` | 全部成功字段、迁移路由、警告与拒绝码 |
| `vault-format.md` 的二进制示例 | 跨端库布局、已认证头部和密码包装可读 |
| `fixtures/backup-v1.2fab` 和关联描述 | 口令认证、完整模型、已上线的格式兼容 |
| `fixtures/interoperability/sources.json` 引用的十个来源文件 | 内容识别、接受的版本、错误口令、完整拒绝、不支持条目的原始序号 |
| `conformance-cases.json` | 来源文件的共同预期结果和对既有 .2fab 的逐字节变异规则 |
| 各端生成的六种交换格式 | 独立 Python 解密与字段比对，含 SHA256/8 位/60 秒及 HOTP 计数器 |

## 适配器约定

- Android 的 Gradle `:core:test` 从仓库根 `spec/` 加载资源，生成 `android/core/build/interoperability/`。Android CI 执行独立读写检查。
- macOS GitHub Actions 使用 `INKOTP_TEST_SPEC` 指向同一目录，用 `INKOTP_TEST_EXPORTS` 指定适配器输出，分别在 ARM64 和 x86_64 上运行。
- iOS 的 Swift XCTest 从仓库根目录读取同一份公共用例。GitHub Actions 上传 iOS 和 Android 各自写出的交换文件，并由对方的生产解码器读取。

适配器输出 `expected.json`（Vault JSON v1）和全套 `<prefix>-<FORMAT>.<extension>` 供 Python 检查。Android、macOS、iOS 的前缀分别为 `kotlin`、`swift`、`ios`；iOS 独立输出目录也可沿用原有的 `swift` 前缀。预期模型使用 `backup-v1.expected.json` 中的两个账号和 RFC 4226 测试密钥的 `Public Counter` / `sample` HOTP 账号，计数器为 10300。独立对拍会先把各端报告的输入模型与这份公共预期比对，再读取写出文件。

文件真实性由上游 Git blob 哈希检查。测试亦对认证数据、文件头、密文、tag 作变异，明确失败原因。XCTest/JUnit 的平台 UI、系统权限与设备测试依各平台另行运行。

## 对端实际产物

Android 分别与 iOS、macOS 互读六份实际导出文件。三个平台使用公开样本独立构造预期数据，检查对端申报的输入，再通过各自的生产解码器识别和导入文件。TOTP/HOTP 的服务、账号、密钥、算法、位数、周期、计数器及顺序须一致。InkOTP 的 .2fab 还须保留完整库模型，包括标签、图标、网址和安全设置。缺少产物、种类不齐或接收失败时，该方向的验收失败。

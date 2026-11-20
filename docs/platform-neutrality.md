# Android 与 iOS 的平台中立性

> 设计文档 §4.4、§5.4、§13.3。Android 先行发布。现有库和备份格式作为 Android 与 iOS 的跨端契约。

## 1. 硬性规则

1. `spec/` 是唯一事实来源；实现按 spec 写，不能反向定义 spec。
2. 库 JSON 与 `2FAV` 头中**不得出现** Android/Kotlin 特有概念：`Uri` 序列化、Java 类名、资源 id、平台异常文本、`Int` 溢出语义。
3. 枚举一律大写字符串；整数显式位宽（u32/i64）；时间用 UTC ISO-8601/Unix 秒；UUID 小写带连字符。
4. 种子内部存原始字节（Base64），对外导出统一规范 Base32；文本 UTF-8 + NFC。
5. `:core` 不得 import `android.*` / `androidx.*`；平台能力通过接口注入。
6. 任何格式变更：`formatVersion/schemaVersion + 1` + 迁移 + `spec/` 更新 + Python 参考实现对拍（C-08/C-27）。

## 2. 自动检查

```bash
# 1) core 无 Android 依赖（模块构建配置保证）
# 2) 源码不得出现 android/androidx import
grep -RInE '^import (android|androidx)\.' android/core/src || true

# 3) Python 验证本地库样本并对拍 Kotlin 交换文件
make crosscheck

# 4) schema 字段白名单/枚举测试（M1 起，:core 单测）
```

CI 应断言第 2 条为零命中；`tools/check_no_internet.py` 额外检查合并 manifest。

## 3. 跨端验收

Android 和 iOS 消费同一份 RFC 4226/6238 向量、解析用例、`2FAV` 规范示例及公开备份夹具。CI 传递两端实际生成的导出文件，使用对方的生产解码器读取，并且用独立 Python 参考实现核对交换账号和验证码参数。文件或参数不兼容时验证整份拒绝、具体错误码及序号。

iOS 用 App Support 和本机 Keychain 管理私有库。`2FAV` 二进制及 Aegis、2FAS、otpauth 文件交换沿用公共契约，只有本机的 Keychain 绑定和系统验证策略独立存放。当前 iOS 范围与真机待测见 [iOS 说明](../ios/README.md)。

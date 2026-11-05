# 平台中立性与 Phase 2（iOS）预备

> 设计文档 §4.4、§5.4、§13.3。v1.0 只实现 Android，但库/备份格式是跨端契约。

## 1. 硬性规则

1. `spec/` 是唯一事实来源；实现按 spec 写，不能反向定义 spec。
2. 库 JSON 与 `.2fab` 头中**不得出现** Android/Kotlin 特有概念：`Uri` 序列化、Java 类名、资源 id、平台异常文本、`Int` 溢出语义。
3. 枚举一律大写字符串；整数显式位宽（u32/i64）；时间用 UTC ISO-8601/Unix 秒；UUID 小写带连字符。
4. 种子内部存原始字节（Base64），对外导出统一规范 Base32；文本 UTF-8 + NFC。
5. `:core` 不得 import `android.*` / `androidx.*`；平台能力通过接口注入。
6. 任何格式变更：`formatVersion/schemaVersion + 1` + 迁移 + `spec/` 更新 + Python 参考实现对拍（C-08/C-27）。

## 2. 自动检查

```bash
# 1) core 无 Android 依赖（模块构建配置保证）
# 2) 源码不得出现 android/androidx import
grep -RInE '^import (android|androidx)\.' android/core/src || true

# 3) Python 与 Kotlin 互读 .2fab（M1 起）
make crosscheck

# 4) schema 字段白名单/枚举测试（M1 起，:core 单测）
```

CI 应断言第 2 条为零命中；`tools/check_no_internet.py` 额外检查合并 manifest。

## 3. iOS Phase 2 启动检查清单

- [ ] 用同一份 `spec/vectors.json` 复跑 RFC 6238/4226 + 边界用例。
- [ ] iOS 读取 Android 导出的 `.2fab`；Android 读取 iOS 导出的 `.2fab`。
- [ ] 10 个账号同一时刻验证码逐位一致。
- [ ] 库 JSON 字段兼容性断言（未知字段忽略、schemaVersion 拒绝规则一致）。
- [ ] 备份错误语义一致（口令错/损坏/版本高/参数超限）。
- [ ] iOS 用 App Group 共享容器 + Keychain（`.biometryCurrentSet`）；AutoFill 独立解锁。
- [ ] 仍不共享代码；共享 spec + 测试向量 + Python 参考实现。

## 4. `ios/` 目录约定

v1.0 只保留占位 README，不创建 Xcode 工程。Phase 2 计划结构：

```
ios/
├── App/                 # SwiftUI 主 App：Features/ Platform/
├── Core/                # Swift Package：OTP/URI/Model/Backup/Crypto
├── CoreTests/
└── AutoFillExtension/   # P1
```

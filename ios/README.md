# iOS（Phase 2 占位）

**v1.0 不实施 iOS。** 本目录只保留位置与计划，不创建 Xcode 工程、不写 Swift 代码。

Phase 2 启动时按设计文档第 9 章 + `spec/` 实现：

- SwiftUI 主 App；Keychain + `SecAccessControl`（`.biometryCurrentSet`）；swift-sodium Argon2id（参数与 Android 一致）。
- App Group 共享容器供 AutoFill 扩展访问库文件；扩展独立解锁。
- Credential Provider Extension（P1）。
- **Step 0**：用 `spec/vectors.json` 复跑向量；与 Android 互读 `.2fab`；10 个账号验证码逐位一致；库 JSON 字段兼容性断言。

详见 [docs/platform-neutrality.md](../docs/platform-neutrality.md)。

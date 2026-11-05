# InkOTP

> 完全离线的 Android 两步验证器（TOTP），设计语言 **Quiet Ink**。
> v1.0 只做 Android；`ios/` 与 `spec/` 已为 Phase 2 预留，跨端只共享规格与测试向量，不共享代码。

## 状态

M0（工程骨架与规格）进行中。设计文档：[docs/design/2FA验证器-技术设计文档.md](docs/design/2FA验证器-技术设计文档.md)。

## 产品底线

- **不联网**：不申请 `INTERNET` 权限，不集成分析/崩溃 SDK。
- **加密存储**：整库 AES-256-GCM；DEK 由 Android Keystore 或恢复口令包装。
- **可恢复**：加密备份 `.2fab`；换机、重装、丢机都能靠它恢复。
- **可审计**：开源（GPL-3.0），依赖最小并锁定版本。

> 验证器只负责**生成**验证码，不负责校验；也无法在忘记恢复口令时帮你找回数据。

## 构建

需要：

- JDK **25**（Eclipse Temurin 25 LTS）
- Android SDK：`platforms/android-37.2`、`platform-tools`、`build-tools`（AGP 9.5 默认 36.0.0）
- 网络（仅首次下载 Gradle wrapper 与依赖；App 本身不联网）

```bash
make setup     # 创建 tools/.venv 并安装 Python 参考实现依赖
make test      # :core 单测 + :app 单测
make build     # :app:assembleDebug
make verify    # 单测 + 构建 + lint + 无网络权限检查 + 设计 token 扫描 + Python 自测
make screenshots  # Roborazzi 录制/校验截图基线
```

也可以直接：

```bash
cd android
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
```

## 目录

```
docs/     设计与工程文档（唯一事实来源）
spec/     平台中立的数据格式、测试向量与用例
tools/    Python 参考实现与检查脚本（不随 App 发布）
android/  Gradle 工程（:core 纯 JVM，:app Compose）
ios/      Phase 2 占位，仅 README
```

## 安全

- 不要提交 keystore、备份文件、真实 secret。
- 漏洞请按 [SECURITY.md](SECURITY.md) 私下披露，不要开公开 issue。

## 许可证

[GPL-3.0](LICENSE)。第三方依赖与字体/图标的许可证见 `docs/` 与 App 内“关于与开源许可”。

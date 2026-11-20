# 工具链与版本锁定

## Android 构建

| 项目 | 仓库配置 |
| --- | --- |
| JDK | JDK 25，GitHub Actions 使用 Eclipse Temurin |
| Gradle | Wrapper 9.8.2 |
| Android Gradle Plugin | 9.5.0 |
| Kotlin | AGP 内置 2.2.10，Compose 和 serialization 插件同版本 |
| Android SDK | `platform-tools`、`platforms;android-37.2`、`build-tools;36.0.0` |
| SDK 级别 | compileSdk 37.2、targetSdk 36、minSdk 26（Android 8.0） |
| JVM 字节码 | Java 和 Kotlin 目标为 17 |
| Python 参考工具 | CI 使用 Python 3.13，依赖由 `tools/requirements.txt` 固定 |

构建配置见 [Gradle Wrapper](../android/gradle/wrapper/gradle-wrapper.properties)、[依赖版本目录](../android/gradle/libs.versions.toml) 和 [App 配置](../android/app/build.gradle.kts)。依赖或工具链升级按技术设计文档 §18.3 记录 ADR，并同步 §18.4 和本表。

| 关键依赖 | 版本 |
| --- | --- |
| Compose BOM | 2026.09.00 |
| activity-compose / lifecycle / core-ktx | 1.13.0 / 2.11.0 / 1.19.1 |
| datastore-preferences / biometric | 1.3.0 / 1.4.0-beta01 |
| CameraX / ZXing core | 1.6.2 / 3.5.4 |
| BouncyCastle（Argon2id） | 1.86 |
| kotlinx-serialization-json / kotlinx-coroutines-android | 1.9.0 / 1.10.2 |
| Robolectric / Roborazzi | 4.17 / 1.77.0 |

Compose 与 Material 3 由 BOM 管理。Kotlin 2.2.10 与 serialization 1.9.0 配套。相机使用 `camera-compose`，以维持离线权限边界。Android 构建与测试入口见 [开发与贡献](development.md)，门禁见 [CI 指南](testing/github-ci.md)。

## 字体与图标

Android 随包提供 Inter 4.1 的 400、500、600 字重子集，保留等宽数字特性。图标是仓库内的 Material Symbols Rounded 矢量资源。字体、图标与对应许可证均在仓库内。需要再生成图标时使用 [生成脚本](../tools/fetch_material_symbols.py)。 [Android 许可证目录](../android/app/src/main/assets/licenses)

## 本机和 CI 的验证范围

Roborazzi 的 JDK 参数配置在 `android/app/build.gradle.kts`。截图在本机生成，CI 运行单测、覆盖率和 Lint。相机、生物识别及实际设备上的覆盖安装按 Android 真机验收说明记录。 [CI 指南](testing/github-ci.md)

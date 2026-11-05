# 工具链与版本锁定

> 实际验证日期：2026-11-05。变更工具链必须按设计文档 §18.3 开 ADR，并同步本文件与 §18.4 版本锁。

## 版本表

| 项 | 版本 | 说明 |
|---|---|---|
| JDK（构建） | **Eclipse Temurin 25.0.5+8 LTS** | Gradle daemon 用 25；Android 字节码目标仍是 17 |
| Gradle | **9.8.2**（Wrapper） | `android/gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | **9.5.0** | min Gradle 9.6.0、min JDK 17、max API 37 |
| Kotlin | **2.2.10（AGP built-in）** | 不要应用 `org.jetbrains.kotlin.android`；Compose/serialization 插件同版本 |
| compileSdk | **37.2**（`release(37) { minorApiLevel = 2 }`） | 当前 AndroidX/Compose 要求 API 37；SDK 包名 `platforms;android-37.2` |
| targetSdk | **36** | Play 2026-08 要求；Android 17 行为变更待评估 |
| minSdk | **26** | Android 8.0 |
| Java/Kotlin 字节码 | **17** | `sourceCompatibility/targetCompatibility = 17`、`jvmTarget = 17` |
| Android SDK | `~/Library/Android/sdk` | platform-tools 37.0.1、build-tools 36.0.0（AGP 默认）/37.0.0、platforms/android-37.2 |
| Python（参考实现） | 3.15（本机） | CI 用 3.13+；依赖见 `tools/requirements.txt` |

## 关键依赖

| 依赖 | 版本 |
|---|---|
| Compose BOM / Material3 | 2026.09.00 / 1.12.1 / 1.4.0 |
| activity-compose / lifecycle / core-ktx | 1.13.0 / 2.11.0 / 1.19.1 |
| datastore-preferences | 1.3.0 |
| biometric | 1.4.0-beta01 |
| CameraX（core/camera2/lifecycle/compose） | 1.6.2 |
| ZXing core | 3.5.4 |
| BouncyCastle（Argon2id） | 1.86 |
| kotlinx-serialization-json | 1.9.0 |
| kotlinx-coroutines-android | 1.10.2 |
| Robolectric / Roborazzi | 4.17 / 1.77.0 |

## 为什么这样锁

- **Kotlin 不能独立升级**：AGP 9.5 的 built-in Kotlin 固定 2.2.10，`kotlin-android` 会被拒绝。
- **serialization 不能 ≥1.10**：1.10 起用 Kotlin 2.3 编译，2.2.10 编译器读不了其元数据；1.9.0 与 2.2.10 对齐。
- **Compose BOM 2026.09.00 要求 compileSdk 37**：36 会在 `checkDebugAarMetadata` 失败。
- **不用 camera-view**：会拉入 `camera-video`/`media3` 并申请 `ACCESS_NETWORK_STATE`；`camera-compose` 无此问题。
- **Argon2 用 BouncyCastle**：纯 Java、JVM 可测、与 Python 参考实现逐字节一致；真机若 >3s 再换 argon2kt（接口不变）。

## UI 资产

- **Inter 4.1** 静态字体子集（400/500/600），保留 `tnum`；OFL-1.1，许可证在 `android/app/src/main/assets/licenses/Inter-LICENSE.txt`。总计约 256KB。
- **Material Symbols Rounded** 36 个矢量图标（Apache-2.0），由 `tools/fetch_material_symbols.py` 从 Google Fonts 拉取并转换为 Android vector；生成物已提交，构建期不联网。
- 字体/图标都属于提交产物；不要用构建期网络下载。

## 常用命令

```bash
# 本机（macOS）
cd android
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew :app:lintDebug

# 根目录
make verify
```

## 新机器准备

1. 安装 Temurin 25（或任意 JDK 25），设置 `JAVA_HOME`。
2. 安装 Android SDK：`platform-tools`、`platforms;android-37.2`、`build-tools;36.0.0`，接受 licenses。
3. 不需要系统 Gradle；Wrapper 会下载 9.8.2。
4. 不需要模拟器；真机 + JVM 测试。
5. `make setup` 建 Python venv。

## 已知约束

- 本机 `adb devices` 当前无设备；生物识别/相机/SAF 的验收需要一个真机。
- Roborazzi 在 JDK 25 下需要 `--add-exports java.base/jdk.internal.access=ALL-UNNAMED` 与 `--add-opens java.base/java.io=ALL-UNNAMED`（已写入 `app/build.gradle.kts`）。
- Gradle 9.8.2 对 AGP/KGP 的 `Configuration.setVisible` 有弃用警告（Gradle 11 前需升级 AGP），不影响当前构建。
- Roborazzi 截图基线暂为本机 macOS 口径；CI 暂不验图。

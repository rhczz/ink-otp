# Android、macOS 和 iOS 共用一个发布页

## 为什么改

Android `v0.5.3`、macOS `macos-v0.1.0-beta.2` 和 iOS `ios-v0.5.3-preview.2` 由各自的标签和工作流创建 Release。用户要下载三个平台的包，需要查找不同的页面，版本号也无法直接对应。

## 范围和验收方式

从 `v0.5.4-beta.1` 起，使用一个 `v{版本}` 标签和一份 GitHub Release。三端从该标签对应的提交构建并通过各自的检查，共用发布版本。Assets 同时列出 Android APK、macOS 通用 DMG、iOS 模拟器 ZIP 和 iPhone/iPad 的 IPA，以及统一的 SHA-256 和发布资料。历史发布继续提供下载，升级测试继续引用已固定的旧包。

验收时核对三个包的版本和各自的签名要求，检查缺包、哈希错误、流程取消及重复发布会阻止公开发布，并在发布后重新下载 Assets 验证校验和。升级验证沿用 Android 固定证书和 Apple 端的覆盖安装检查。iOS 真机签名、macOS 公证和实际硬件使用情况按本次运行的结果分别记录。

## 验证结果

本地 27 项发布工具测试通过，覆盖三端资产集与统一哈希、缺件、错误版本、哈希篡改、稳定版签名门禁、旧平台标签、同一次运行的失败任务重试和重复发布。六个工作流已通过 actionlint 检查，唯一排除项是此工具版本尚未识别的 GitHub `concurrency.queue` 字段。三个修改过的打包脚本通过 Bash 语法检查。

macOS 构建号取 Android `versionCode + 100`。首版为 111，高于 [独立 macOS Beta 2](https://github.com/rhczz/ink-otp/actions/runs/44468564960) 的构建号 39。iOS 构建号从 2 提升到 3。

本机 `make ci-gradle`、`make ci-python` 和 `make ci-quality` 均通过，包含 46 项 Python 测试及 Android 的权限、体积与对齐检查。用 GitHub 实际发布历史验证了 8 个统一命名标签和 4 个 Apple 历史标签，Android 构建号 11 高于历史最大值 10。

[发布标签运行](https://github.com/rhczz/ink-otp/actions/runs/44509204423) 的 Android 核心 110 项、App 183 项测试通过。核心行覆盖率 94.70%，分支 70.91%。App 行覆盖率 84.17%，分支 60.95%。iPhone 模拟器 XCTest 19 项通过，iOS 核心行覆盖率 92.22%，App 行覆盖率 51.66%。macOS 的 Apple Silicon 与 Intel 核心行覆盖率均为 89.8%（1542/1718）。本次 Apple 报告未提供分支覆盖率。

macOS 用正式 DMG 覆盖安装，桌面升级和交互共 7 项 XCTest 通过。iPad 模拟器从历史预览源码重建的旧包升级到正式 ZIP，保留密文并通过 Keychain 读回原账号。正式 ZIP 卸载重装后可重建并重新打开账号库。Android 与 Apple 端的备份互读检查通过。

[InkOTP 0.5.4-beta.1](https://github.com/rhczz/ink-otp/releases/tag/v0.5.4-beta.1) 已公开为预发布。重新下载 7 项资产，`SHA256SUMS` 的 6 条校验全部通过。`build-info.json` 与发布标签的提交和运行匹配。Android APK 确认为版本代码 11，签名证书与既有版本一致。iOS IPA 和模拟器 ZIP 都写入完整版本 `0.5.4-beta.1`，构建号 3。macOS DMG 校验通过，挂载后确认应用版本相同，构建号 111，同时包含 Apple Silicon 与 Intel，并通过临时签名验证。

Android 与 iOS 真机安装、覆盖升级、系统认证、相机和性能未验证。本次 iOS IPA 未签名，macOS 为临时签名。Apple 分发签名、macOS 公证和实际硬件上的迁移未验证。

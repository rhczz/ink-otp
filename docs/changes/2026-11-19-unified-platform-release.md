# Android、macOS 和 iOS 共用一个发布页

## 为什么改

Android `v0.5.3`、macOS `macos-v0.1.0-beta.2` 和 iOS `ios-v0.5.3-preview.2` 由各自的标签和工作流创建 Release。用户要下载三个平台的包，需要查找不同的页面，版本号也无法直接对应。

## 范围和验收方式

从 `v0.5.4-beta.1` 起，使用一个 `v{版本}` 标签和一份 GitHub Release。三端从该标签对应的提交构建并通过各自的检查，共用发布版本。Assets 同时列出 Android APK、macOS 通用 DMG、iOS 模拟器 ZIP 和 iPhone/iPad 的 IPA，以及统一的 SHA-256 和发布资料。历史发布继续提供下载，升级测试继续引用已固定的旧包。

验收时核对三个包的版本和各自的签名要求，检查缺包、哈希错误、流程取消及重复发布会阻止公开发布，并在发布后重新下载 Assets 验证校验和。升级验证沿用 Android 固定证书和 Apple 端的覆盖安装检查。iOS 真机签名、macOS 公证和实际硬件使用情况按本次运行的结果分别记录。

## 验证结果

本地 27 项发布工具测试通过，覆盖三端资产集与统一哈希、缺件、错误版本、哈希篡改、稳定版签名门禁、旧平台标签、同一次运行的失败任务重试和重复发布。六个工作流已通过 actionlint 检查，唯一排除项是此工具版本尚未识别的 GitHub `concurrency.queue` 字段。三个修改过的打包脚本通过 Bash 语法检查。

macOS 构建号取 Android `versionCode + 100`。首版为 111，高于 [独立 macOS Beta 2](https://github.com/rhczz/ink-otp/actions/runs/44468564960) 的构建号 39。iOS 构建号从 2 提升到 3。

正式标签运行、公开 Assets 与下载校验待记录。Android 和 iOS 真机专项未验证。

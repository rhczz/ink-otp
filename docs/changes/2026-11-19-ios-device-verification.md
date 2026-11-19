# iOS 真机目标构建与升级验收

[上一版 iOS 预览](2026-11-18-ios-native-parity.md) 发布了双架构模拟器应用。仓库尚未配置 Apple 分发签名，普通验证也没有编译 iPhoneOS 目标。即使模拟器使用 ARM64，模拟器产物也无法用于 iPhone 签名安装。

本轮在 GitHub Actions 用 iPhoneOS SDK 归档 Release 应用，核对 Mach-O 的目标平台、架构和应用标识，再生成可供签名的未签名 IPA。预发布页分别标识模拟器和真机包，正式版仍要求经过 Apple 签名。验收以实际云端归档、独立解包核对和发布资产为准。真机签名、安装及硬件操作待验证。

升级路径使用上一版公开的模拟器包建库并添加公开测试账号，再在相同模拟器上覆盖安装本轮构建，检查原 Keychain 和账号能否继续使用。剪贴板的立即清理、自动锁定后的归属判断，以及诊断页的采样与最近操作耗时，也按 Android 现有行为核对。

普通流程在 iPhone 模拟器上运行，升级场景选用 iPad。相册扫码使用 Android 测试中的同一条公开 URI 生成二维码图片，交给 iOS 实际使用的 ImageIO 与 Vision 识别，并检查无二维码和损坏图片的反馈。

首次 [GitHub Actions 归档](https://github.com/rhczz/ink-otp/actions/runs/44461628645) 已通过 iPhoneOS 归档及独立解包，支持 iPhone 与 iPad，最低部署版本为 17.0。升级任务改为按公开 ZIP 的原样安装，两份模拟器应用均保持下载时的内容。候选包由 Xcode 使用模拟器签名构建。设备分发签名及真机实测仍待验证。

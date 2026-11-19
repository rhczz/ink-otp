# 开发与问题记录

这里记录项目每次修改的原因、行为变化和验证结果，也记录问题排查的结论。读者应能从记录看懂这次工作解决了什么，以及哪些结论仍需要验证。

| 日期 | 记录 | 状态 |
|---|---|---|
| 2026-11-19 | [iOS 录屏与后台遮挡](2026-11-19-ios-screen-capture-curtain.md) | 3 项模拟器测试通过，录屏与镜像真机待测 |
| 2026-11-19 | [iOS 真机目标构建与升级验收](2026-11-19-ios-device-verification.md) | iPhoneOS 归档、iPhone 模拟器及 iPad 模拟器升级和重装通过，设备签名待验证 |
| 2026-11-19 | [Mac 切换应用、解锁与添加账号](2026-11-19-macos-desktop-lifecycle.md) | GitHub Actions 验收中 |
| 2026-11-19 | [Android 与 macOS 实际交换备份](2026-11-19-macos-cross-platform.md) | GitHub Actions 验收中 |
| 2026-11-18 | [iOS 原生版与跨平台测试](2026-11-18-ios-native-parity.md) | 首版已发布，模拟器 Keychain 问题见后续记录 |
| 2026-11-18 | [InkOTP macOS 桌面版与公共测试集](2026-11-18-macos-native-parity.md) | 双架构测试与通用 DMG 验证通过，真机待测 |
| 2026-11-18 | [InkOTP 0.5.3 原生离线隐私声明](2026-11-18-native-offline-privacy-0.5.3.md) | 本机验证通过，真机待复测 |
| 2026-11-18 | [InkOTP 0.5.2 同步内置隐私声明](2026-11-18-privacy-sync-0.5.2.md) | 本机验证通过，真机待复测 |
| 2026-11-18 | [官网隐私页移除个人姓名](2026-11-18-website-name-removal.md) | 本机静态检查通过，待部署 |
| 2026-11-18 | [InkOTP 隐私声明核查与 Android 离线阅读](2026-11-18-android-offline-privacy.md) | 本机验证通过，真机与平台审核待验证 |
| 2026-11-18 | [InkOTP 官网与隐私声明](2026-11-18-official-website.md) | 本机网页验证通过，域名待部署 |
| 2026-11-18 | [InkOTP 0.5 Android 公测](2026-11-18-android-public-beta-0.5.md) | 本机门禁通过，真机专项待验证 |
| 2026-11-18 | [Android 进门验证、隐私与设置修复](2026-11-18-android-entry-privacy-and-settings.md) | 本机验证通过，真机待复测 |
| 2026-11-17 | [隐藏复制修复与无恢复口令锁定核查](2026-11-17-hidden-copy-and-device-lock.md) | 0.4.2-beta.1 已发布并下载验签，隐藏复制待手机复测 |
| 2026-11-17 | [0.4.1 GitHub 发布与下载复核](2026-11-17-release-0.4.1.md) | 远端 CI、签名发布及下载复核通过 |
| 2026-11-17 | [0.4 手机反馈：系统指纹入口、闪屏与到期颜色](2026-11-17-phone-feedback-fixes.md) | 0.4.1-beta.1 已发布，真机复测待补 |
| 2026-11-17 | [修复 GitHub CI 的 Android SDK 初始化](2026-11-17-ci-sdk-bootstrap-fix.md) | 真实远端 CI 通过 |
| 2026-11-17 | [GitHub 质量门禁与 tag 发布](2026-11-17-ci-and-release.md) | 默认分支 CI、tag 发布及下载复核通过 |
| 2026-11-17 | [M4：标准互通、每次进入验证与性能收口](2026-11-17-m4-standards-auth-performance.md) | 0.4.0-beta.1 已发布，手机专项待测 |
| 2026-11-17 | [M3：从演示界面接入真实验证器](2026-11-17-m3-local-beta.md) | 用户初步试用正常，专项验收待补 |
| 2026-11-17 | [架构、数据存储、密钥保护与卸载清理](2026-11-17-architecture-storage-and-uninstall.md) | 已核对源码、APK 与 Android 规则 |

## 如何新增记录

文件名使用 `YYYY-MM-DD-主题.md`。先描述用户遇到的问题或希望得到的能力，再说明改动、验证结果和剩余事项。小改动可以简短，复杂改动按用户流程展开；不要求每篇套用相同数量的标题。

涉及故障时，说明现象、复现条件、原因、修复和回归验证。仅做调查时，记录证据、结论及没有确认的部分。不要在文档里放真实密钥、验证码、口令或个人备份。

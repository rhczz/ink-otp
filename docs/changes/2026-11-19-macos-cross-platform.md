# Android 与 macOS 实际交换备份

## 为什么做

[macOS 首次发布](2026-11-18-macos-native-parity.md)已验证两端的输出可由 Python 参考实现读取。用户迁移验证码时，接收文件的是另一端的应用。本次用两端的实际编解码器直接互读，覆盖这个交接环节。

## 范围与验收

- 在 GitHub Actions 中由 Android 和 macOS 各自产生六种交换文件。双方使用同一组公开测试账号，包含标准 TOTP、非默认算法、位数与周期，以及带计数器的 HOTP。
- 两个接收端分别检查真实对端产物的识别、解密和账号参数。旧版 .2fab 应保留完整库模型。对端产物缺失或不符时，接收检查必须失败。
- 既有 Python 独立对拍继续运行。保留 iOS 客户端对同一套样本的接入和它与 Android 的双向测试。

## 实际结果

[首次完整双向验证](https://github.com/rhczz/ink-otp/actions/runs/44456484471)中，Apple Silicon 和 Intel 分别读取 Android 写出的六种文件。Android 使用 Kotlin 生产解码器读取 Apple Silicon 写出的六种文件，两个生产端同时通过 Python 独立解码。HOTP 计数器为 10300，旧 .2fab 还核对了库设置、标签、图标、网址和时间戳。全部样本使用公开密钥。该次运行中桌面操作另行跟踪。

在这次运行中，两种架构的 Swift 内核行覆盖率均为 89.8%（1542/1718）。Swift 工具链未生成分支覆盖率。[对应 Android 报告](https://github.com/rhczz/ink-otp/actions/runs/44456484475)显示内核行 1447/1528、分支 892/1258，应用行 3904/4638、分支 1725/2830。

[iOS 实现已合入](https://github.com/rhczz/ink-otp/pull/9)。该版本的工作流已验证同一套公开样本及与 Android 的交换文件。[iOS 工作流](https://github.com/rhczz/ink-otp/actions/runs/44455412785)

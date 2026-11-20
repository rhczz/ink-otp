# InkOTP macOS 桌面版与公共测试集

## 为什么做

Android 已发布到 0.5.3。本次为桌面用户增加离线 Mac 客户端，使 OTP、备份交换、安全设置和 Android 保持一致，并使用 Mac 的窗口、菜单、系统认证和文件访问方式。

## 原来怎样

Android 有原生客户端和既有协议夹具。仓库尚无 macOS 应用、Mac 构建流程或安装包。

## 本次范围与验收

以 [桌面规格](https://github.com/rhczz/ink-otp/blob/2227cb28b3043fe30564196008fd1ef37e119abe/spec/macos-parity.md) 为准。GitHub Actions 负责编译、公共测试、应用打包和发布；本机只编写源码。按共同夹具验证双向交换、异常和资源边界。真实硬件检查独立记录。

## 现在怎样

桌面端使用分栏窗口、搜索与快捷键管理账号；支持手动输入、URI、图片、相机和文件拖入。Quiet Ink 仍用于验证码与锁定界面。支持六种导入导出格式，导入可先预览再选择合并方式。切换应用时按设置锁库，复制内容保留至清理时限以便切换到目标 App 粘贴。

本机库位于应用沙箱的 Application Support/InkOTP。指针文件切换前，新一代库与对应的 Keychain 包装已经写入。锁定救援替换时保留前一代库和所需的旧设备凭据。已验证的恢复口令仍能解开旧版可移植备份；本机设备凭据随设备保留。

## 实际结果

- [macOS GitHub Actions](https://github.com/rhczz/ink-otp/actions/runs/44449043570) 在 Apple Silicon 与 Intel 分别通过公共协议、独立 Python 验证六种导出格式及 Release 构建。Swift 内核两架构均记录行覆盖率 89.3%（1,535 / 1,718），该工具链未报告 Swift 分支覆盖率。
- 同一次运行在沙箱 App 中验证了设备钥匙串、恢复口令、错误口令拒绝、并发文件锁与救援副本。Actions 还挂载并验证通用 DMG、双架构 Mach-O、签名和权限。
- Android 原有门禁和新增公共用例已在 [Android CI](https://github.com/rhczz/ink-otp/actions/runs/44446401730) 通过。iOS 的共享向量已在独立分支接入，整套新增公共合约的结果未验证。
- 公测采用临时签名，版本标签为 `macos-v0.1.0-beta.1`。正式签名与 Apple 公证待仓库配置。没有在本机安装工具链、构建、打包或执行自动测试。

## 真机待测

- 从下载的 DMG 首次安装与覆盖升级，检查 Keychain 身份连续性和便携备份恢复。覆盖 Apple Silicon、Intel 及最低支持的 macOS 14。
- 验证系统睡眠、切换用户、密码与 Touch ID 的成功、拒绝和取消。临时签名公测根据 [EX-008](../exceptions.md) 暂停每次进入验证，正式签名版本需单独完成硬件验收。
- 测试允许、拒绝和撤回相机权限，验证无相机设备可使用图片或手动输入。
- 用真实 Finder 文件面板及文件拖放验证导入、取消、覆写确认和导出。用 Android 0.5.3、Aegis 和 2FAS 实际打开 Mac 导出文件。
- 用 VoiceOver、浅深色与窗口缩放复查。隐藏复制应只提示“已复制”。测试后台锁定、系统锁屏、复制时限和其他应用覆盖剪贴板。
- 多账号的内存、刷新节奏和能耗需要实机记录。

平台机制见 [Apple 的 macOS Keychain 技术说明](https://developer.apple.com/documentation/technotes/tn3137-on-mac-keychains) 与 [Gatekeeper 用户说明](https://support.apple.com/en-us/102445)。

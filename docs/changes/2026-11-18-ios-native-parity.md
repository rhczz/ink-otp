# iOS 原生版与跨平台测试

Android v0.5.3 已发布。iOS 目录此前只有 Phase 2 计划。本次以该 Android 版本为行为基线，用 iOS 原生技术栈落地同一产品，并在 GitHub Actions 上建立构建、验证、打包和发布入口。

范围与验收见 [iOS 互通与验收规格](../../spec/ios-parity.md)。开发机仅用于代码与仓库操作。两个平台使用同一份公共向量及独立格式夹具。iOS 模拟器结果和真机签名、硬件验收分别记录。

## 实际结果

首轮 [GitHub Actions](https://github.com/rhczz/ink-otp/actions/runs/44447570052) 验证了 Swift 公共测试（执行 22 项，通过 21 项，Android 产物互读在独立任务通过）。Android 核心测试报告 108 项，行 94.634%、分支 70.747%。App 报告 183 项，行 84.174%、分支 60.954%。Xcode 首轮在 SwiftUI 根视图表达式超时，已拆分表达式并复跑。第二轮 Xcode 构建和 5 项模拟器仓储测试通过，XCUITest 在原生确认控件匹配时失败，已调整测试选择器。完整验证见 [PR #9 的最新检查](https://github.com/rhczz/ink-otp/pull/9/checks)。签名 IPA 和 iPhone 真机验收另行验证。

第三轮 GitHub Actions 的模拟器仓储测试 7 项全部通过，含取消导入、认证策略和密钥轮换。Swift 核心生产代码覆盖 1770/1927 行（91.85%）。该次 Xcode 26.3 工具链未输出 Swift 分支计数。初次建库的界面测试仍在修正与重跑。 [第三轮 Actions](https://github.com/rhczz/ink-otp/actions/runs/44449617249)

锁定状态下恢复会保存一份原加密库。启用系统验证时，库内导入重新认证并轮换数据密钥。用户取消认证时继续停留在原表单。系统文件面板只在导出期间使用受文件保护的临时目录，冷启动和清除库时清理残留。

## 技术决策

iOS 使用通用 2FAV v1 二进制库。平台专属的 Keychain 标识与验证策略以单个原子写入的本机文件保存，完整性标签覆盖版本域、标识、策略和库内容。系统验证开启时使用 Keychain 的设备密码级保护及人机验证。换设备使用可移植交换文件。

Apple 框架提供 AES-GCM、系统随机源和 PBKDF2。现有跨端格式另要求 Argon2id 和 scrypt，采用固定版本的参考 C 源代码，随应用离线构建，保留来源、许可和校验文件。XcodeGen 仅用于在 Actions 生成 Xcode 项目。iOS 对标准截屏的限制由系统决定，应用控制后台快照遮挡和录屏遮挡。

## 三端公共测试集

合入最新 `master` 后，iOS 的 XCTest 直接运行 Android 和 macOS 使用的 `spec/conformance-cases.json`。同一份文件列出十个外部文件的接收或拒绝结果，以及八种已有 `.2fab` 的字节变异。独立交换验证先核对三端报告的模型是否等于公共样本，再解码并逐项比对六种格式。

模拟器以真实 Keychain 读取并删除本机专用密钥，仓储测试共 8 项通过。XCUITest 从接受隐私声明走到恢复口令建库和手动填写账号，发现键盘会遮住原来的底部保存按钮。保存与取消改用 iOS 导航栏原生操作。 [iOS Keychain 与界面试跑](https://github.com/rhczz/ink-otp/actions/runs/44451603299)

最新公共契约在 macOS ARM64、Intel 及通用磁盘映像上通过，平台流程分别提交验收记录。 [macOS Actions](https://github.com/rhczz/ink-otp/actions/runs/44452716880)

# iOS 真机目标构建与升级验收

[上一版 iOS 预览](2026-11-18-ios-native-parity.md) 发布了双架构模拟器应用。仓库尚未配置 Apple 分发签名，普通验证也没有编译 iPhoneOS 目标。即使模拟器使用 ARM64，模拟器产物也无法用于 iPhone 签名安装。

本轮在 GitHub Actions 用 iPhoneOS SDK 归档 Release 应用，核对 Mach-O 的目标平台、架构和应用标识，再生成可供签名的未签名 IPA。预发布页分别标识模拟器和真机包，正式版仍要求经过 Apple 签名。验收以实际云端归档、独立解包核对和发布资产为准。真机签名、安装及硬件操作待验证。

升级验收从上一版公开的模拟器包及对应源码出发，在同一模拟器建立公开测试账号，再覆盖安装候选包，检查原 Keychain 和账号能否继续使用。另在卸载后重新安装候选 ZIP 并从头建库。剪贴板的立即清理、自动锁定后的归属判断，以及诊断页的采样与最近操作耗时，也按 Android 现有行为核对。

普通流程在 iPhone 模拟器上运行，升级场景选用 iPad。相册扫码使用 Android 测试中的同一条公开 URI 生成二维码图片，交给 iOS 实际使用的 ImageIO 与 Vision 识别，并检查无二维码和损坏图片的反馈。

首次 [GitHub Actions 归档](https://github.com/rhczz/ink-otp/actions/runs/44461628645) 已通过 iPhoneOS 归档及独立解包，支持 iPhone 与 iPad，最低部署版本为 17.0。候选模拟器 ZIP 由 Xcode 使用模拟器签名构建，按原样安装。设备分发签名及真机实测仍待验证。

首轮 iPhone XCTest 发现正常二维码返回空结果，PNG 截断后也被 ImageIO 容错读取。iOS 在识别时为虚拟机选择支持的 CPU，Vision 识别失败时再使用 Core Image，并检查 PNG 结束块。另一个断言发现原子替换文件后读取的大小过时，诊断和读取上限现通过文件系统即时查询每个路径的大小。测试继续与两份文件的实际字节数比对。[失败用例与原始日志](https://github.com/rhczz/ink-otp/actions/runs/44461768307/job/135350000561)

首次 iPad 作业完成了候选双架构包构建及验签，并安装了旧版包。Xcode 随后拒绝在模拟器使用只适用于物理设备的测试配置。升级作业改为使用标准模拟器配置，每一阶段都指向对应 ZIP 中解出的应用，并比较安装前后的可执行文件，避免开发用的 Debug 产品混入。[首次 iPad 运行](https://github.com/rhczz/ink-otp/actions/runs/44464194447/job/135357894126)

修复后 iPhone 17 Pro 模拟器在 iOS 26.2 上执行 19 项 XCTest，全部通过。包括 320 与 2560 像素的二维码 PNG、空白图、截断或超限输入、恢复文件大小、剪贴板归属，以及原有的开户和重启流程。iOS App 行覆盖率为 2736/5292（51.70%），Swift 核心为 1770/1927（91.85%）。Swift 工具链未输出分支覆盖率。模拟器证据不能代替在真实相机、屏幕录制或 Face ID 上的操作。[iPhone 复验日志](https://github.com/rhczz/ink-otp/actions/runs/44464194447/job/135357893937)

同批 Android CI 的 JaCoCo 报告中，核心覆盖 1446/1528 行（94.63%）和 890/1258 个分支（70.75%），应用层覆盖 3904/4638 行（84.17%）和 1725/2830 个分支（60.95%）。[Android 验证与报告](https://github.com/rhczz/ink-otp/actions/runs/44465887779/job/135363453085)

直接安装 preview.1 的公开 ZIP 后，系统会在建库时提示设备密钥不可用。上一版关闭了模拟器产品签名，常规 Debug 测试未能覆盖这一发布差异。本轮用旧标签 `ios-v0.5.3-preview.1` 对应的固定源码和 Xcode 的模拟器签名重建旧版，再验证覆盖安装。候选 ZIP 的实际内容还要通过卸载后重装建库测试。两次安装使用的均为模拟器身份，不能用于 Apple 真机分发。[公开旧包的实际失败](https://github.com/rhczz/ink-otp/actions/runs/44465887779/job/135363453807)

# iOS 原生版与跨平台验收

行为基准为 Android v0.5.3（e91ed34）。应用层继续分离界面、状态管理、仓储、平台服务和纯数据契约。iOS 采用 Swift、SwiftUI、LocalAuthentication、Security、AVFoundation、Vision、PhotosUI 和系统文件面板。UI 沿用 Quiet Ink 颜色与布局语义，支持简体中文、英文、深色模式和系统辅助功能。

迁移入口采用 `.2fab`、Aegis、2FAS 和逐行 `otpauth://`。沿用原有版本、字段、算法、大小限制及错误语义。设备包装与系统认证由各平台负责。iOS 沙盒内只保存经过认证加密的库，关闭应用自动云备份。iOS 使用系统认证、限时且仅本机的剪贴板和切后台遮挡。系统不提供应用级截屏拦截，录屏或镜像时遮挡敏感内容。已发布 Android 范围之外的 AutoFill 独立排期。

验收覆盖隐私声明、建库、解锁与取消、自动锁定、清除确认、搜索排序、手输与二维码、计时和复制、条目编辑删除、主题设置、诊断与关于、备份预览及合并。对口令错误、文件损坏、取消、重复条目、并发请求和版本升级分别验证。专用硬件能力另列真机实测。

iOS、Android 和 macOS 消费仓库内同一套 [公共协议测试集](conformance.md)，包括 `vectors.json`、`otpauth-cases.json`、`conformance-cases.json` 和 `fixtures/`。平台适配器逐个断言向量结果，并由独立 Python 参考实现验证跨端导出的内容。新增夹具由参考工具生成。GitHub Actions 执行 Android 检查、iOS XCTest、模拟器构建和打包与 GitHub Release。真机安装包需要 Apple 签名证书和相匹配的 provisioning profile。模拟器产物以明确的模拟器标签交付。

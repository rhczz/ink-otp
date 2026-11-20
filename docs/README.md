# InkOTP 文档

## 使用 InkOTP

- [下载安装](../README.md)与[隐私声明](https://inkotp.renhongchang.top/privacy/)
- [TOTP 的工作原理](totp.md)
- [备份格式与兼容矩阵](../spec/interoperability.md)及 [Google Authenticator 二维码迁移](../spec/google-authenticator-transfer.md)
- [iOS 安装说明](../ios/README.md)与 [macOS 安装说明](../macos/README.md)

## 开发与发布

- [开发指南](development.md)介绍仓库结构、构建和本地检查。
- [工具链](toolchain.md)列出版本和环境要求。[CI 指南](testing/github-ci.md)说明自动检查及报告。
- [发布指南](releasing.md)和[发布检查表](release-checklist.md)说明三端打包、签名和验证流程。
- [协作规则](../AGENTS.md)和[例外登记](exceptions.md)说明文档与变更要求。

## 设计与规格

- [技术设计](design/2FA验证器-技术设计文档.md)记录架构、设计决策和工程约束。
- [规格索引](../spec/README.md)汇总数据契约、平台行为和公共测试向量。[跨平台契约](platform-neutrality.md)说明本地库与交换文件的边界。
- [威胁模型](threat-model.md)及[标准与互通原则](standards-and-interoperability.md)说明安全假设和格式选择。

## 变更历史

[开发与问题记录](changes/README.md)保存改动原因、验证结果和仍待验证的范围。[版本发布](https://github.com/rhczz/ink-otp/releases)按版本提供说明和下载。

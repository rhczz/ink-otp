# 安全问题报告

请通过 GitHub 私密表单提交应用版本、平台和系统版本、影响范围，以及使用公开测试数据的复现步骤。请勿上传真实密钥、验证码、恢复口令或用户备份。 [私下报告漏洞](https://github.com/rhczz/ink-otp/security/advisories/new)

普通使用问题可以提交公开 issue。报告旧版问题时，请注明原版本，并在可行时用同平台的新版本复核。 [问题列表](https://github.com/rhczz/ink-otp/issues) · [下载](https://github.com/rhczz/ink-otp/releases)

InkOTP 在设备上生成验证码，不上传账号或备份。账号密钥加密存放在本机，备份与迁移由用户发起。系统认证、剪贴板、备份和设备被攻破后的边界见下列文档。 [威胁模型](docs/threat-model.md) · [隐私声明](https://inkotp.renhongchang.top/privacy/)

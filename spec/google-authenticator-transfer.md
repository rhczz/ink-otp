# Google Authenticator 二维码迁移

Google Authenticator 使用「转移验证码／转移账号」生成一张或多张二维码。InkOTP 在相同二维码流程中双向迁移，沿用现有本地库及导入确认。Google 账号同步由 Google Authenticator 自身管理。HOTP 的密钥及计数器可迁移和再次导出，当前 InkOTP 只生成 TOTP 验证码。[Google 帮助](https://support.google.com/accounts/answer/1066447)

## 数据

迁移 URI 使用 `otpauth-migration://offline?data=…`。`data` 是百分号编码的标准 Base64 protobuf，输入也接受无填充或 URL-safe 的 Base64。Google 未公开此传输协议的规范。以下字段根据实际导出和可互操作实现确认。[固定的 Aegis protobuf](https://github.com/beemdevelopment/Aegis/blob/d6f4e5925a97e4e91593f1542085eae03432a759/app/src/main/proto/google_auth.proto) · [当前版本 2 的观察记录](https://github.com/miso-develop/m5authenticator/issues/128) · [Google 兼容导出限制](https://github.com/beemdevelopment/Aegis/blob/master/app/src/main/res/values/strings.xml)

| 消息 | 字段 |
| --- | --- |
| MigrationPayload | 1 账号（重复字段），2 版本（读取 1/2，写出 1），3 批次页数，4 从 0 起的页序，5 int32 批次 ID |
| OtpParameters | 1 原始密钥，2 账号名称，3 服务名，4 算法（SHA1=1、SHA256=2、SHA512=3），5 位数（6=1、8=2），6 类型（HOTP=1、TOTP=2），7 HOTP 非负 int64 计数器，8 可选 Google 条目标识（忽略该元数据） |

缺省或零值的算法、位数、类型按 SHA1、6 位、TOTP 处理。TOTP 周期固定 30 秒。名称含冒号且服务名为空时，或存在和服务名完全相同的 `服务名:` 前缀时，导入拆成服务和账号。导入拒绝越界、损坏、未知版本和无法表示的条目。文件格式和 Google QR 不携带 InkOTP 本地标签、图标、网址或设备设置。

## 交互

- 导入通过拍摄、图片或已有的粘贴入口接收二维码。多页使用相同版本、批次 ID 和页数。页序可以乱序，完全相同页可重扫，冲突或混批需要明确反馈。集齐后仅显示一次账号预览，通过已有合并入口一次提交。取消、离开流程及锁定清除批次。每个批次最多 500 页、500 条。
- 导出以独立的「Google Authenticator」入口展示二维码。展示前要求用户明确确认密钥会明文显示，并沿用平台身份确认。逐页显示页码，单页不超过 10 条和 2000 字节 URI。面向 Google Authenticator 的导出使用可互通的 SHA1、6 位及 30 秒 TOTP（HOTP 亦可）。无法无损表示的条目必须给出明确原因，通过用户主动选择才可只迁移支持的账号。二维码随关闭及锁定清除，敏感内容遵循平台现有隐私遮挡。
- 当备份导入允许合并或替换时，使用有清晰边距的独立操作和可滚动预览。合并是主要操作。替换前提示会移除现有账号并再次确认。
- 验证码列表仅在本次解锁进入时做一次短程、非循环入场。首批卡片由上到下依次就位，单卡动画 220ms，错开 16ms，最多 6 张，整体 300ms。搜索、倒计时和滚动不会重播。遵循系统减少动态效果，锁定与遮挡立即执行。

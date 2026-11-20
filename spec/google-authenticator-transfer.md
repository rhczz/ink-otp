# Google Authenticator 二维码迁移

Google Authenticator 使用「转移验证码／转移账号」生成一张或多张二维码。InkOTP 在相同二维码流程中双向迁移，沿用现有本地库及导入确认。Google 账号同步由 Google Authenticator 自身管理。HOTP 的密钥及计数器可迁移和再次导出，当前 InkOTP 只生成 TOTP 验证码。[Google 帮助](https://support.google.com/accounts/answer/1066447)

## 数据

迁移 URI 使用 `otpauth-migration://offline?data=…`。`data` 是百分号编码的标准 Base64 protobuf，输入也接受无填充或 URL-safe 的 Base64。Google 未公开此传输协议的规范。以下字段根据实际导出和可互操作实现确认。[固定的 Aegis protobuf](https://github.com/beemdevelopment/Aegis/blob/d6f4e5925a97e4e91593f1542085eae03432a759/app/src/main/proto/google_auth.proto) · [当前版本 2 的观察记录](https://github.com/miso-develop/m5authenticator/issues/128) · [Google 兼容导出限制](https://github.com/beemdevelopment/Aegis/blob/master/app/src/main/res/values/strings.xml)

| 消息 | 字段 |
| --- | --- |
| MigrationPayload | 1 账号（重复字段），2 版本（读取 1/2，写出 1），3 批次页数，4 从 0 起的页序，5 int32 批次 ID |
| OtpParameters | 1 原始密钥，2 账号名称，3 服务名，4 算法（SHA1=1、SHA256=2、SHA512=3），5 位数（6=1、8=2），6 类型（HOTP=1、TOTP=2），7 HOTP 非负 int64 计数器，8 可选 Google 条目标识（忽略该元数据） |

缺省或零值的算法、位数、类型按 SHA1、6 位、TOTP 处理。TOTP 周期固定 30 秒。名称含冒号且服务名为空时，或存在和服务名完全相同的 `服务名:` 前缀时，导入拆成服务和账号。导入拒绝越界、损坏、未知版本和无法表示的条目。文件格式和 Google QR 不携带 InkOTP 本地标签、图标、网址或设备设置。

## 导入

- 「添加账号」的相机、从相册导入及手动输入自动识别 Google Authenticator 迁移链接，并进入同一个批次收集流程。单账号设置链接和 Base32 仍走原有添加流程。
- 「导入备份」通过已有的系统文件选择器读取迁移二维码图片，或读取 UTF-8 文本中的一个或多个迁移链接，每个非空行一个链接，属于同一批次。混入其他格式或无效内容时提示错误，保留原库。普通备份继续由现有格式检测与预览处理。锁定的既有库收到迁移数据时提示先解锁后合并，库恢复的替换入口不接受迁移合并。
- 每批最多 500 页、500 条。同一批次的版本、批次 ID 和页数须一致。页序可乱序，完全相同的页可以重读，冲突或混批时显示提示。收齐后进入已有账号预览，只允许一次确认合并。取消、离开流程和锁定均清除待导入批次。

## 导出

- 在「导出备份」的格式选择中选择「迁移二维码（图片）」。展示前核对格式兼容性，要求用户确认明文风险并验证当前恢复口令。不可无损迁移的条目显示原因，经用户明确选择后才只导出兼容账号。
- 面向 Google Authenticator 的迁移导出使用 SHA1、6 位、30 秒 TOTP 或非负 int64 计数器的 HOTP。单页最多 10 条及 2000 字节 URI，界面标注当前页及总页数。
- 每页可直接展示给目标设备扫描，也可通过系统文件选择器保存为 PNG。Google 公开的换机入口为「转移账号」中的扫描二维码。在另一台设备打开保存的图片即可供其扫描。 [Google 帮助](https://support.google.com/accounts/answer/1066447)
- QR 内容含明文密钥。系统选择器只保存用户主动确认的页面，取消后可返回该页，保存失败时显示提示。关闭预览或锁定时清除待展示的二维码，离开前台时沿用 Android 隐私遮挡。

普通备份的合并与替换确认、验证码卡片入场见 [Android 交互规格](android-interaction.md)。

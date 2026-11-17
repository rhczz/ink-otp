# 验证器数据交换规格

状态：核心格式测试与独立 Python 对拍通过；尚未把新导出文件逐一安装到来源应用做人工回归。

## 契约

- 保留原 `vault.bin` / `.2fab` v1，新增交换器不得改变旧文件含义。
- 单账号 URI 按现有 `otpauth://` 解析规则；多行文本逐行解析，出错行明确拒绝，不跳过后当成功。
- Aegis、2FAS 格式以各自官方公开实现为依据，按受支持版本读取，不把扩展名作为唯一判断条件。
- 文件大小、账号数、KDF、解密输出及字段长度先限制后使用；错误输入不修改现有库。
- 不支持的类型、字段映射损失、算法和参数必须在导入/导出前明确说明，不静默转换成不同验证码。
- 明文交换文件包含账号种子，必须由用户主动选择并确认；常规备份优先使用已验证的加密格式。
- 导入预览、合并和本地重新加密流程继续由仓储执行。交换格式不携带本机 Keystore 密钥或强制进入验证策略。

## 支持矩阵与测试依据

| 格式 | 读取 | 写出 | 加密与限制 |
|---|---|---|---|
| Aegis JSON | 外层 v1，库内容 v1–3，明文或口令加密 | 外层 v1 / 库内容 v3，明文或口令加密 | 官方 scrypt N=32768、r=8、p=1；AES-256-GCM 两层密钥包装；nonce/tag 按官方分别存储 |
| 2FAS `.2fas` JSON | schema 1–4，明文或口令加密 | schema 4，明文或口令加密 | 官方 PBKDF2-HMAC-SHA256 / 10000 次 / 256 位；AES-GCM；不擅自增加迭代数以免破坏互通，也不将它描述为等同本机 Argon2id |
| 多行 `otpauth://` 文本 | 每个非空行一个标准 URI | 每行一个 URI | 明文，使用 UTF-8 和百分号编码；支持 TOTP/HOTP，不接受缺少账号的裸密钥文件 |
| InkOTP `.2fab` | 原 v1 | 原 v1 | 委托原 BackupCodec；既有 Argon2id/NFC 契约保持不变 |

默认交换导出为 **Aegis 加密 JSON**。明文 JSON 与 URI 文本含完整账号种子，调用界面必须单独风险确认；格式层通过 `plaintext` 属性与警告标识。外部加密文件按来源口令原样解密，不做 NFC、trim 或大小写变换；本 App 使用当前恢复口令导出时，由调用层遵循本 App 的 NFC 规范。

系统文档导出的 MIME 与扩展名配套：Aegis 为 `application/json` / `.json`，URI 为 `text/plain` / `.txt`，2FAS 与旧备份为 `application/octet-stream` / `.2fas`、`.2fab`。2FAS 官方 [导出与分享](https://github.com/twofas/2fas-android/blob/2395e3782f0d098e1491c095ff45f8ea8a05e751/feature/backup/src/main/java/com/twofasapp/feature/backup/ui/export/BackupExportScreen.kt) 使用通用类型及 `.2fas`；不自行注册不存在的专用 MIME。将 `.2fas` 配成 JSON MIME 可能导致系统保存器追加 `.json`，本实现通过通用类型避免 AOSP 默认保存器的这类重命名；第三方文件提供器仍可能有自己的命名行为。

### 账号与字段边界

- 交换保留 TOTP/HOTP 类型、issuer、账号、种子、SHA1/SHA256/SHA512、6/8 位、TOTP 周期与 HOTP counter；周期范围 1–300 秒，种子解码后 1–255 字节。
- Aegis/URI 的 counter 支持 0…`Long.MAX_VALUE`；2FAS 官方 DTO 的 counter 为 Int，所以 2FAS 读取/写出明确限制为 0…2147483647。
- Steam、MOTP、Yandex、7 位等当前模型不支持的条目，**整份文件拒绝**并给出从 1 开始的条目序号；不跳过后宣称成功。未知 OTP 参数字段同样拒绝。未知非 OTP 元数据允许读取，但通过警告明确提示未保留。
- 来源图标、备注、收藏、分组、应用专有字段，以及本机解锁/Keystore/安全设置，不承诺随交换格式迁移；导入、导出结果均携带 warnings。携带原库 JSON 字段的备份仍可选 `.2fab`；导入后仍在本机重新加密，不恢复旧设备 Keystore。
- 2FAS schema 1/2 未记录的参数按其官方默认值处理，且提示旧格式默认值警告。账号本地 UUID 和导入时间由目标库重建。

### 输入与密钥预算

- 文件与解密输出最大 12 MiB，最多 10000 条；严格 UTF-8，JSON 深度最多 32、容器总数最多 100000，非字符串内的逗号/冒号合计最多 500000。解析前扫描预算，避免浅层标量数组绕过容器限制；额外复杂的元数据也可能触发此边界。URI 用行序列逐行处理，空行不建立完整列表，每行最多 16384 字符，Base32 文本最多 2048 字符，再验证解码长度。
- Aegis 最多读取 32 个 slots，其中最多 4 个口令 slots；只使用官方 type=1 口令通道。scrypt 的 N 必须为 2 的幂，r=1…32、p=1…4，估算内存 `128*N*r` ≤64 MiB，工作量 `N*r*p` ≤524288。所有成本先校验再派生；超限为 `UNSUPPORTED_KDF`，而非“口令错误”。
- 2FAS 的 salt 接受 16…512 字节、写出为官方 Android 使用的 256 字节；GCM nonce 为 12 字节，tag 为 16 字节。Base64 只容忍 ASCII 空白，不丢弃其它垃圾字符。
- 临时口令 CharArray、派生密钥、解密明文 ByteArray 在使用后擦除。JVM 不可变 String 无法保证即时覆写；不把主要数组清理承诺成没有任何内存残留。结果模型的种子由调用方在取消、导入完成、锁定时清理。

### 公共接口

`InterchangeCodec.detect(bytes)` 返回 `InterchangeFormat`；`decode(bytes, password?)` 返回 `ImportedVault(model, format, warnings)`；`encode(model, format, password?)` 返回 `ExportedVault(bytes, format, warnings)`。口令参数使用后消费并清零。`InterchangeException` 仅含固定安全消息、错误码和条目序号，不包含原文件或种子。

格式枚举为 `AEGIS_ENCRYPTED`、`AEGIS_PLAINTEXT`、`TWOFAS_ENCRYPTED`、`TWOFAS_PLAINTEXT`、`OTPAUTH_TEXT`、`INKOTP_2FAB`；分别提供 `extension`、`requiresPassword`、`plaintext`。

### 互操作证据

公开样本在 [fixtures/interoperability/](fixtures/interoperability/)；[sources.json](fixtures/interoperability/sources.json) 记录逐文件 Git blob hash、固定来源 commit 和公开测试密码。样本来自 Aegis 官方 importer tests；其测试说明记录了把公开测试二维码扫入来源 App 后再真实导出的过程。加密样本口令 **`test`** 来自同仓库的测试代码，非真实用户口令。

- 固定 Aegis 来源 commit：`d6f4e5925a97e4e91593f1542085eae03432a759`。[格式规范](https://github.com/beemdevelopment/Aegis/blob/d6f4e5925a97e4e91593f1542085eae03432a759/docs/vault.md)、[真实导出测试过程与口令](https://github.com/beemdevelopment/Aegis/blob/d6f4e5925a97e4e91593f1542085eae03432a759/app/src/test/java/com/beemdevelopment/aegis/importers/DatabaseImporterTest.java)。
- 固定 2FAS 实现 commit：`2395e3782f0d098e1491c095ff45f8ea8a05e751`。[BackupContent](https://github.com/twofas/2fas-android/blob/2395e3782f0d098e1491c095ff45f8ea8a05e751/data/services/src/main/java/com/twofasapp/data/services/domain/BackupContent.kt)、[BackupService](https://github.com/twofas/2fas-android/blob/2395e3782f0d098e1491c095ff45f8ea8a05e751/data/services/src/main/java/com/twofasapp/data/services/domain/BackupService.kt)、[加密流程](https://github.com/twofas/2fas-android/blob/2395e3782f0d098e1491c095ff45f8ea8a05e751/core/cipher/src/main/java/com/twofasapp/cipher/backup/BackupCipherImpl.kt)、[KDF](https://github.com/twofas/2fas-android/blob/2395e3782f0d098e1491c095ff45f8ea8a05e751/core/cipher/src/main/java/com/twofasapp/cipher/backup/internal/BackupKeyGeneratorPbkdf.kt)。

已运行：核心交换测试 **19/19**；先观察新增 API 缺失的 RED，再观察 counter/Base64/JSON 预算回归的 3 个 RED，修正后全部通过。外部 Unicode 口令原样使用、URI 原行号、2FAS 排序三项回归也已通过整体验证。另加浅层标量数组预算与大量空行两项资源回归，待最终整体验证。官方混合样本中包含 7 位、Steam 的文件，测试验证解密成功后明确整份拒绝，而非把它们算作全部账号已支持。

独立检查命令：

```bash
tools/.venv/bin/python tools/reference_interchange.py --exports android/core/build/interoperability
```

该脚本不导入 Kotlin、不执行上游脚本；用 Python hashlib + cryptography 独立验证 10 个上游文件的 hash、Aegis/2FAS 官方明密样本的 profile 一致性，再真实解密 Kotlin 六种格式导出，与固定公开账号的完整 OTP 参数逐项比较，结果 **PASS**。尚未在 Aegis/2FAS App UI 内手工导入这些新导出文件，不把格式测试描述为覆盖全部应用版本。

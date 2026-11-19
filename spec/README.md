# `spec/` — 平台中立规格与测试向量

macOS 接入与公共验收范围见 [桌面版规格](macos-parity.md)。

本目录是 InkOTP 跨平台契约的**唯一事实来源**。Android、Phase 2 iOS 与 Python 参考实现都只能消费本目录，不能各自"发明"格式。

| 文件 | 内容 |
|---|---|
| `vectors.json` | OTP / Base32 / 边界条件的机器可读测试向量（由 Python 参考实现生成并复核） |
| `otpauth-cases.json` | `otpauth://` 解析器的 40 个一致性用例与错误码 |
| `backup-format.md` | 可移植加密备份 `.2fab` v1（已冻结） |
| `vault-format.md` | 设备本地加密库 `vault.bin` v1（已冻结） |
| `android-interaction.md` | Android 系统认证、无闪屏操作反馈、验证码到期及相册授权回归边界 |
| `fixtures/` | 固定参数、可复现的 `.2fab` 测试夹具 |

> 规则：**不要手改 `vectors.json` 或 `fixtures/`**。它们由 `tools/reference_totp.py` / `tools/reference_backup.py` 生成；手改会与生成器、`crosscheck` 和 Kotlin 单测三方不一致。

---

## 1. 重新生成与验证

```bash
# 一次性：创建 Python venv（已被 .gitignore 忽略）
make setup
# 等价于
python3 -m venv tools/.venv
tools/.venv/bin/pip install -r tools/requirements.txt

# 1) 参考实现自测（RFC 4226 / 6238 / 4648 + 域限制）
tools/.venv/bin/python tools/reference_totp.py --selftest

# 2) 重新生成测试向量（固定 seed=20261105，输出应稳定）
tools/.venv/bin/python tools/reference_totp.py --generate-vectors spec/vectors.json

# 3) 备份实现自测 + 重新生成 fixture（固定 salt/nonce，输出应逐字节稳定）
tools/.venv/bin/python tools/reference_backup.py --selftest
tools/.venv/bin/python tools/reference_backup.py --create-fixture

# 4) 全量交叉检查（不依赖 Android）
tools/.venv/bin/python tools/crosscheck.py --spec spec --backup-dir /tmp/m0-empty

# 5) JSON 语法
python3 -m json.tool spec/vectors.json > /dev/null
python3 -m json.tool spec/otpauth-cases.json > /dev/null
python3 -m json.tool spec/fixtures/backup-v1.meta.json > /dev/null
```

`--selftest` 会同时读取 `spec/vectors.json` 逐条复核；文件不存在时只跑 RFC/域限制自测并打印 `SKIPPED`。CI/发布门槛应保证文件存在且 `crosscheck` PASS。

---

## 2. `vectors.json` 结构

顶层：

```json
{
  "version": 1,
  "meta": { "seed": 20261105, "...": "..." },
  "totp": [ ... ],
  "hotp": [ ... ],
  "base32": [ ... ],
  "boundary": [ ... ],
  "random_totp": [ ... ]
}
```

### 2.1 `totp`（18 条）

RFC 6238 Appendix B：6 个时间点 × 3 种算法。

```json
{
  "time": 59,
  "algorithm": "SHA1",
  "digits": 8,
  "period": 30,
  "secretAscii": "12345678901234567890",
  "secretBase32": "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
  "expected": "94287082"
}
```

- `secretBase32` 是**规范 RFC 4648 Base32**：大写、必要时带 `=` 填充。解析器还必须接受无填充、小写、空格、连字符（见 §2.3）。
- SHA1/SHA256/SHA512 的 secret 分别为 20/32/64 字节的 ASCII `1234567890...`；不要混用长度。
- `time` 是 Unix 秒；`counter = floor(time / period)`。

### 2.2 `hotp`（10 条）

RFC 4226 Appendix D：`counter = 0..9`，SHA1，6 位。

```json
{
  "counter": 0,
  "algorithm": "SHA1",
  "digits": 6,
  "secretAscii": "12345678901234567890",
  "secretBase32": "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
  "expected": "755224"
}
```

### 2.3 `base32`

```json
{ "input": "MZXW6YTB", "expectedHex": "666f6f6261", "expectedBase32": "MZXW6YTB", "note": "RFC 4648" }
{ "input": "MZXW6YTB!", "expectedError": "BASE32_INVALID_CHAR" }
```

- `expectedHex` 是解码后的**原始字节**（十六进制小写）。
- `expectedBase32` 是这些字节的**规范编码**（大写、必要时带 `=` 填充），用于验证 encode 方向；容错输入也归一到该值。
- 有效输入按**容错模式**解码：去 ASCII 空白与 `-`、转大写、允许省略填充；填充若存在必须完全规范（长度是 8 的倍数，填充数正确），且末尾未使用位必须为 0。
- 无效输入给出 `expectedError`；错误码见 §4.1。

### 2.4 `boundary`

边界用例用"请求对象 + 预期错误码"表达，便于 Kotlin/Python 直接消费，而不是靠自然语言：

```json
{
  "description": "digits=5 is not supported",
  "request": {
    "kind": "totp",
    "time": 59,
    "secretHex": "3132333435363738393031323334353637383930",
    "algorithm": "SHA1",
    "digits": 5,
    "period": 30
  },
  "expectedError": "DIGITS_UNSUPPORTED"
}
```

- `kind`：`totp` / `hotp`。
- `secretHex`：原始 secret 字节的十六进制（空字符串表示空 secret）。
- TOTP 请求字段：`time/secretHex/algorithm/digits/period`。
- HOTP 请求字段：`counter/secretHex/algorithm/digits`。
- 参考实现：`tools/reference_totp.py` 的 `code_from_request(request)`。
- 每个用例只让一个字段越界，便于定位。

### 2.5 `random_totp`（72 条）

固定种子 `20261105` 生成，**期望值来自独立 Python 实现**，不是从 Kotlin 抄的。覆盖：

- 算法：`SHA1` / `SHA256` / `SHA512`
- `digits`：`6` / `8`
- `period`：`1` / `30` / `60` / `300`
- secret 长度：`1..64` 字节（逐长度覆盖）
- 时间包含 `0/1/29/30/59/60/1111111109/4102444800` 等边界，其余为确定性伪随机。

```json
{
  "time": 0,
  "algorithm": "SHA1",
  "digits": 6,
  "period": 1,
  "secretBase32": "AA======",
  "expected": "000000"
}
```

---

## 3. `otpauth-cases.json` 结构

文件是 JSON 数组，每个用例：

```json
{
  "name": "standard_totp_uri",
  "input": "otpauth://totp/GitHub:me%40example.com?secret=...",
  "accept": true,
  "error": null,
  "expected": { "...": "..." }
}
```

- `accept = true`：`error` 必须为 `null`，`expected` 为对象。
- `accept = false`：`error` 为非空字符串，`expected = null`。
- 成功解析的普通条目：

```json
{
  "type": "totp",
  "issuer": "GitHub",
  "accountName": "me@example.com",
  "secretBase64": "MTIzNDU2Nzg5MDEyMzQ1Njc4OTA=",
  "algorithm": "SHA1",
  "digits": 6,
  "period": 30,
  "counter": null,
  "warnings": []
}
```

- HOTP：`type = "hotp"`，`period = null`，`counter` 为整数。
- TOTP：`counter = null`。
- `warnings` 始终存在；未知 warning 必须忽略（向前兼容）。
- 裸 Base32 输入（设计文档 §5.3 允许）返回草稿：

```json
{
  "kind": "bare-secret",
  "type": "totp",
  "issuer": null,
  "accountName": null,
  "secretBase64": "...",
  "algorithm": "SHA1",
  "digits": 6,
  "period": 30,
  "counter": null,
  "needsUserInput": ["issuer", "accountName"],
  "warnings": []
}
```

- `otpauth-migration://` 不解析 protobuf，只路由：

```json
{ "kind": "migration", "host": "offline", "dataBase64": "SGVsbG8" }
```

### 3.1 解析语义（与用例绑定）

1. **scheme 大小写不敏感**；只接受 `otpauth` 与路由 `otpauth-migration`。其他 scheme → `SCHEME_UNSUPPORTED`。
2. **host 必须是 `totp` / `hotp`**（大小写不敏感）；其他 → `HOST_UNSUPPORTED`。
3. **label 拆分**：先在**原始** label 上按第一个字面 `:` 拆成 issuer/account，再分别百分号解码。这样 `%3A` 属于字段内容，不会变成分隔符；`My%3AIssuer:alice` -> issuer `My:Issuer`，`GitHub%3Aalice` -> account `GitHub:alice`。
4. **issuer 优先级**：query `issuer` 优先；它与 label 前缀都非空且不相等时，追加 warning `ISSUER_MISMATCH`（不阻断）。query 缺失时用 label 前缀；两者都没有则 `issuer = null`。
5. **文本规范化**：百分号解码后做 Unicode NFC；去除 C0/C1 控制字符并追加 `CONTROL_CHARS_STRIPPED`；issuer 长度 ≤ 128、accountName ≤ 256（NFC 后的 Unicode 码点数）。超限分别返回 `LABEL_ISSUER_TOO_LONG` / `LABEL_ACCOUNT_TOO_LONG` / `ISSUER_TOO_LONG`。
6. **secret**：Base32 容错解码（大小写、空格、连字符、`=` 填充）；缺失 → `SECRET_MISSING`；空串 → `SECRET_EMPTY`；解码后 1..255 字节，超限 → `SECRET_TOO_LONG`；非法 Base32 → `SECRET_INVALID_BASE32`。成功时统一存为**原始字节的标准 Base64（带填充）**。
7. **算法**：大小写不敏感，只允许 SHA1/SHA256/SHA512；缺省 SHA1；MD5 等 → `ALGORITHM_UNSUPPORTED`。
8. **digits**：只允许 6/8；缺省 6；5/7 等 → `DIGITS_UNSUPPORTED`。
9. **period**：1..300；缺省 30；0/301 等 → `PERIOD_OUT_OF_RANGE`。
10. **counter**：仅 HOTP；缺省 0；负数 → `COUNTER_NEGATIVE`；> `2^64-1` → `COUNTER_OVERFLOW`；非整数 → `COUNTER_INVALID`。
11. **`image` 与其他未知参数**：静默忽略，不联网加载。
12. **裸 Base32**：输入不含 `://` 且能容错解码为 1..255 字节时，按 `bare-secret` 草稿返回；issuer/account 由用户补填。
13. **安全**：解析器不得把原始 URI、secret、验证码写入日志/异常信息。

### 3.2 解析错误码

| 错误码 | 含义 |
|---|---|
| `URI_MALFORMED` | URI 语法/百分号转义非法 |
| `SCHEME_UNSUPPORTED` | 非 `otpauth` / 非迁移 scheme |
| `HOST_UNSUPPORTED` | host 不是 `totp`/`hotp`，或 host 缺失 |
| `LABEL_EMPTY` | label 为空 |
| `LABEL_ACCOUNT_EMPTY` | `issuer:` 后 account 为空 |
| `LABEL_ISSUER_TOO_LONG` | label 前缀 issuer > 128 码点 |
| `LABEL_ACCOUNT_TOO_LONG` | account > 256 码点 |
| `ISSUER_TOO_LONG` | query `issuer` > 128 码点 |
| `SECRET_MISSING` | 没有 `secret` 参数 |
| `SECRET_EMPTY` | `secret=` 为空 |
| `SECRET_INVALID_BASE32` | Base32 字符/长度/填充/尾位非法 |
| `SECRET_TOO_LONG` | 解码后 > 255 字节 |
| `ALGORITHM_UNSUPPORTED` | 非 SHA1/SHA256/SHA512（如 MD5） |
| `DIGITS_UNSUPPORTED` | 非 6/8（如 5、7） |
| `PERIOD_OUT_OF_RANGE` | 不在 1..300 |
| `COUNTER_NEGATIVE` | HOTP counter < 0 |
| `COUNTER_OVERFLOW` | HOTP counter > `2^64-1` |
| `COUNTER_INVALID` | HOTP counter 非整数 |

Warning 枚举：

| warning | 含义 |
|---|---|
| `ISSUER_MISMATCH` | query issuer 与 label 前缀不一致，已采用 query 值 |
| `CONTROL_CHARS_STRIPPED` | 文本中的控制字符已被移除 |

---

## 4. OTP 域错误码（供 `vectors.json` 与 Kotlin 共用）

### 4.1 Base32 / 请求

| 错误码 | 含义 |
|---|---|
| `BASE32_INVALID_CHAR` | 含非 Base32 字符 |
| `BASE32_INVALID_LENGTH` | 长度无法表示整字节 |
| `BASE32_INVALID_PADDING` | 填充位置/数量非法 |
| `BASE32_INVALID_TRAILING_BITS` | 末尾未使用位非 0 |
| `SECRET_HEX_INVALID` | 测试请求中的 `secretHex` 非法（工具内部） |
| `REQUEST_INVALID` | 测试请求不是对象（工具内部） |
| `KIND_UNSUPPORTED` | 请求 kind 不是 totp/hotp |

### 4.2 OTP 计算

| 错误码 | 含义 |
|---|---|
| `SECRET_EMPTY` | secret 为空 |
| `SECRET_TOO_LONG` | secret > 255 字节 |
| `DIGITS_UNSUPPORTED` | 不是 6/8 |
| `PERIOD_OUT_OF_RANGE` | 不在 1..300 |
| `ALGORITHM_UNSUPPORTED` | 不是 SHA1/SHA256/SHA512 |
| `COUNTER_NEGATIVE` | counter < 0 |
| `COUNTER_OVERFLOW` | counter > `2^64-1` |
| `TIME_OUT_OF_RANGE` | TOTP 时间不在 `0..2^63-1` Unix 秒 |

> TOTP 时间上限定为 `2^63-1`，保证 `time // period` 在所有平台的 signed 64-bit 计数器中安全。HOTP counter 仍按 **u64 大端**编码。

---

## 5. 备份与库格式错误码

完整定义见 `backup-format.md` / `vault-format.md`。前缀约定：

| 前缀 | 格式 | 示例 |
|---|---|---|
| `BAK_E_*` | `.2fab` 备份 | `BAK_E_AUTH`、`BAK_E_KDF_PARAMS` |
| `VLT_E_*` | `vault.bin` | `VLT_E_WRAP_AUTH`、`VLT_E_VAULT_AUTH` |

备份与库的 GCM 认证失败都必须"先验证、后解析"；`BAK_E_AUTH` 不区分口令错误与篡改。

---

## 6. `fixtures/`

| 文件 | 大小 | SHA-256 |
|---|---:|---|
| `backup-v1.2fab` | 1476 B | `8f68c9679df531f9e73c36cafe5a84019654d51f775081939b70634f36c040b1` |
| `backup-v1.expected.json` | 1410 B | `d400ad29e91ac39f21d35316942b2bda30db42f0c8634d2092954d3bc0189297` |
| `backup-v1.meta.json` | 650 B | `cd35e7c0600b162313579e3a53c2d041007362c8b276ea1d1bb61f0594d80868` |

- 口令：`correct horse battery staple`（一次性测试口令）。
- KDF：Argon2id v1.3，`m=65536`、`t=3`、`p=2`；salt/nonce 固定，见 meta。
- 明文：`schemaVersion=1`，2 个条目（SHA1/6/30 与 SHA256/8/60）。
- 固定 salt/nonce **只用于 fixture**；生产导出必须随机。
- 修改 fixture 必须通过 `reference_backup.py --create-fixture` 重新生成，并同步更新 README 中的哈希。

### 6.1 Kotlin → Python 交叉检查契约

Android 构建后可以把 Kotlin 生成的备份放到 `--backup-dir`：

```text
<backup-dir>/
  kotlin-backup.2fab            # 必需（存在时才检查）
  kotlin-backup.expected.json   # 必需（与解密结果做语义 JSON 比较）
  kotlin-backup.meta.json       # 可选；{"password": "..."}
```

- 未提供 meta 时使用文档口令 `correct horse battery staple`。
- 文件不存在时 `crosscheck` 打印 SKIP，M0 不因此失败。
- 只要文件存在但解密/JSON 不匹配，`crosscheck` 退出非 0。

---

## 7. 平台中立性

- JSON 枚举用大写字符串；整数显式 `u32`/`i64`/`u64`；布尔用 JSON `true/false`。
- 文件中所有整数大端序；文本 UTF-8 + NFC。
- 禁止加入 Android/iOS 特有字段；新增字段必须提升版本并保持旧实现"拒绝未知版本"。
- Phase 2 iOS 必须能读取 `.2fab` 与 `vault.bin` v1；设备包装（Keychain/Keystore）是平台本地实现，不属于跨平台文件。

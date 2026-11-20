# InkOTP 规格与测试向量

Android、iOS 和 macOS 的公共数据契约在本目录维护。独立参考实现和生产代码使用同一组公共向量与夹具。

| 主题 | 规格与样本 |
| --- | --- |
| TOTP、HOTP 与 Base32 | [算法测试向量](vectors.json)、[TOTP 原理](../docs/totp.md) |
| 账号设置链接 | [URI 验收用例](otpauth-cases.json)、[字段与错误码](#3-otpauth-casesjson-结构) |
| 备份和迁移 | [交换格式与兼容矩阵](interoperability.md)、[Google Authenticator 二维码](google-authenticator-transfer.md) |
| 本地加密库 | [`2FAV` 格式](vault-format.md)、[跨平台边界](../docs/platform-neutrality.md) |
| 平台行为 | [Android](android-interaction.md)、[iOS/iPadOS](ios-parity.md)、[macOS](macos-parity.md) |
| 验收与发布 | [公共验收](conformance.md)、[发布契约](release.md) |
| 历史格式 | [旧版 `.2fab`](backup-format.md) |

请通过 `tools/reference_totp.py` 生成 OTP 向量，并通过 `tools/generate_google_transfer_fixture.py` 生成 Google 迁移样本。历史夹具保留原始字节。

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

# 3) Google 迁移样本复核（用 --write 重新生成）
tools/.venv/bin/python tools/generate_google_transfer_fixture.py

# 4) 全量交叉检查（不依赖 Android）
tools/.venv/bin/python tools/crosscheck.py --spec spec

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

- 通用 URI 解析器对 `otpauth-migration://` 返回迁移路由，后续由 [Google 专用编解码器](google-authenticator-transfer.md) 处理负载：

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

## 5. 库格式错误码

`VLT_E_*` 见 [本地库格式](vault-format.md)。GCM 认证成功后才能解析库明文。

## 6. 交换与历史备份样本

`fixtures/interoperability/` 和 `fixtures/google-authenticator-v1.json` 用于目前的交换测试。`fixtures/backup-v1.*` 保存 0.5 及更早版本的历史参考字节，其中的 `backup-v1.expected.json` 也是独立交换测试的公共输入模型。历史密文和变异资料仅供复现旧版本。

## 7. 平台中立性

- JSON 字段、枚举字面量和整数范围按各自格式定义。`2FAV` 的整数为大端序，库 JSON 使用 UTF-8。
- 公共格式只包含规格定义的字段。设备包装和认证状态由各平台在应用私有存储中维护。
- 库格式发生不兼容变化时，更新对应版本并验证既有样本和升级路径。

## 8. 验收范围

三端消费同一份 OTP、URI 和 Google 迁移样本，并使用 [公共验收契约](conformance.md) 检查实际备份。系统身份验证、安装和覆盖升级见各平台规格及对应[变更记录](../docs/changes/README.md)。

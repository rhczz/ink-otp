# 本地加密库格式 v1（`2FAV`）

本规范定义 Android 本地加密库 `2FAV` v1 的平台中立字节布局。换机、重装和跨平台迁移使用[可移植备份与迁移格式](interoperability.md)。

## 1. 目标与范围

Android 用数据密钥（DEK）经 AES-256-GCM 加密库 JSON，并把 `2FAV` 写入应用私有的 `vault.bin`。

本规范约束加密负载。设备密钥包装、系统身份验证访问控制和当前会话由平台管理，见 [§7](#7-设备包装)。[本地库与可移植格式](../docs/platform-neutrality.md)说明库文件与可移植备份的边界。

---

## 2. 字节布局

所有整数均为**大端序（big-endian）**；长度单位为字节。

### 2.1 有口令通道（`flags` bit0 = 1，`wrappedDekLen = 32`）

| 偏移 | 长度 | 字段 | 类型 | 说明 |
|---:|---:|---|---|---|
| 0 | 4 | `magic` | bytes | ASCII `2FAV`（`32 46 41 56`） |
| 4 | 1 | `formatVersion` | u8 | 固定 `0x01` |
| 5 | 1 | `flags` | u8 | bit0 = 1：存在口令通道；其余位保留且必须为 0 |
| 6 | 1 | `kdfAlgo` | u8 | `1` = Argon2id v1.3（version 19） |
| 7 | 4 | `mKiB` | u32 BE | Argon2 内存成本，KiB |
| 11 | 4 | `tIter` | u32 BE | Argon2 迭代次数 |
| 15 | 4 | `pLanes` | u32 BE | Argon2 并行度 |
| 19 | 16 | `salt` | bytes | 口令 KDF 盐 |
| 35 | 12 | `dekNonce` | bytes | 包装 DEK 的 GCM nonce |
| 47 | 12 | `vaultNonce` | bytes | 加密库 JSON 的 GCM nonce |
| 59 | 4 | `wrappedDekLen` | u32 BE | 固定 `32` |
| 63 | 32 | `wrappedDekCiphertext` | bytes | `AES-256-GCM(KEK, DEK)` 的密文部分 |
| 95 | 16 | `wrappedDekTag` | bytes | 包装 DEK 的 GCM 标签 |
| 111 | 4 | `ctLen` | u32 BE | 库 JSON 密文字节数 |
| 115 | `ctLen` | `vaultCiphertext` | bytes | `AES-256-GCM(DEK, vault JSON)` 的密文部分 |
| 115+`ctLen` | 16 | `vaultTag` | bytes | 库 JSON 的 GCM 标签 |

恒等式：

```text
fileLen = 115 + ctLen + 16
```

### 2.2 无口令通道（`flags` = 0，`wrappedDekLen = 0`）

无口令时不存在 KEK，也不存在包装的 DEK，因此 `wrappedDekTag` **不存在**，后续字段整体前移 16 字节：

| 偏移 | 长度 | 字段 | 值/说明 |
|---:|---:|---|---|
| 0 | 4 | `magic` | `2FAV` |
| 4 | 1 | `formatVersion` | `1` |
| 5 | 1 | `flags` | `0` |
| 6 | 1 | `kdfAlgo` | `0`（未使用） |
| 7 | 4 | `mKiB` | `0` |
| 11 | 4 | `tIter` | `0` |
| 15 | 4 | `pLanes` | `0` |
| 19 | 16 | `salt` | 全 `0x00` |
| 35 | 12 | `dekNonce` | 全 `0x00` |
| 47 | 12 | `vaultNonce` | 随机 12 B（仍然必须唯一） |
| 59 | 4 | `wrappedDekLen` | `0` |
| 63 | 4 | `ctLen` | u32 BE |
| 67 | `ctLen` | `vaultCiphertext` | `AES-256-GCM(DEK, vault JSON)` 的密文部分 |
| 67+`ctLen` | 16 | `vaultTag` | 库 JSON 的 GCM 标签 |

恒等式：

```text
fileLen = 67 + ctLen + 16
```

> **偏移说明（重要）**：通用公式 `79+N` / `83+N` 是在 `wrappedDekTag` 存在的假设下写出的。v1 明确 `wrappedDekTag` 仅在 `N > 0` 时存在；`N = 0` 时没有该 16 字节字段，因此 `ctLen` 位于 63、密文位于 67，`AAD_vault` 取实际头部 `bytes[0..67)`。这与"tag present iff N>0"一致，也避免留下 16 字节未定义保留区。实现不得在 `N=0` 时写入 16 字节零标签。

### 2.3 字段一致性规则

| 条件 | 允许值 | 违反时错误码 |
|---|---|---|
| `flags` 的 bit1~bit7 | 必须为 0 | `VLT_E_FLAGS` |
| `flags` bit0 = 1 | `kdfAlgo=1`，`wrappedDekLen=32`，KDF 参数在范围内，`salt` 16 B、`dekNonce` 12 B | `VLT_E_KDF_ALGO` / `VLT_E_KDF_PARAMS` / `VLT_E_WRAPPED_LEN` |
| `flags` bit0 = 0 | `kdfAlgo=0`，`mKiB=tIter=pLanes=0`，`salt` 16 B 零，`dekNonce` 12 B 零，`wrappedDekLen=0` | `VLT_E_FLAGS` / `VLT_E_KDF_PARAMS` / `VLT_E_WRAPPED_LEN` |
| `wrappedDekLen` | 只能是 `0` 或 `32` | `VLT_E_WRAPPED_LEN` |
| `ctLen` | 1 ~ 8 MiB | `VLT_E_CT_LEN` |
| 文件长度 | 必须精确等于对应公式 | `VLT_E_FILE_TRUNCATED` / `VLT_E_TRAILING_DATA` |

`flags` bit0=1 但 `wrappedDekLen=0`（或反之）、无口令但 KDF 字段非零、有口令但 `kdfAlgo=0`，都属于**不一致文件**，必须拒绝。

---

## 3. AAD 与 AEAD

| 用途 | 算法 | 密钥 | nonce | AAD |
|---|---|---|---|---|
| 包装 DEK | AES-256-GCM | `KEK`（32 B） | `dekNonce` | `AAD_wrap = bytes[0..63)` |
| 加密库 JSON | AES-256-GCM | `DEK`（32 B） | `vaultNonce` | 有口令：`bytes[0..115)`；无口令：`bytes[0..67)`（见下方说明） |

- `AAD_wrap` 覆盖到 `wrappedDekLen`（含）为止的 63 字节：`magic/version/flags/kdfAlgo/KDF 参数/salt/dekNonce/vaultNonce/wrappedDekLen`。
- `AAD_vault` 覆盖整个头部直到 `ctLen`（含）：有口令时为 `bytes[0..115)`，无口令时为 `bytes[0..67)`。它包含头部中的包装信息（若有）与 `ctLen`。
- 标签均为 16 B，分别存放在 `wrappedDekTag` 与 `vaultTag`；GCM 库输出的 `ciphertext||tag` 必须拆分后按布局写入。
- 所有头部字段都被某个 AAD 覆盖；任何头部篡改都会导致对应 GCM 校验失败（除非先被结构校验拒绝）。

### 3.1 KEK 派生

**Argon2id v1.3**，低层 API 显式指定参数：

| 项 | v1 导出固定值 | 读取接受范围 |
|---|---:|---:|
| `mKiB` | 65536 | 8192 ~ 524288 |
| `tIter` | 3 | 1 ~ 10 |
| `pLanes` | 2 | 1 ~ 8 |
| `salt` | 16 B 随机 | 固定 16 B |
| 输出 | 32 B | 固定 32 B |
| 约束 | — | `mKiB >= 8 * pLanes` |

口令处理使用**Unicode NFC 规范化后按 UTF-8 编码**，不 trim、不改大小写。

---

## 4. 解密顺序（强制）

实现必须严格按以下顺序，任何一步失败都不得继续：

1. **文件大小**：`<= 12 MiB`；至少包含最小头部与 1 字节密文 + 16 B 标签。
2. **结构校验**：`magic`、`formatVersion`、`flags`、`kdfAlgo`、KDF 参数范围、`wrappedDekLen` 与 `flags` 一致性、`ctLen` 范围。
3. **长度校验**：按有/无口令公式精确匹配 `fileLen`，不允许多余尾随字节。
4. **派生 KEK**（仅当 `flags` bit0=1）：Argon2id -> 32 B KEK。
5. **解包 DEK**（仅当 `flags` bit0=1）：`AESGCM(KEK).decrypt(dekNonce, wrappedDekCiphertext || wrappedDekTag, aad=AAD_wrap)`；**先验证 GCM 标签**，通过后才得到 32 B DEK。
   - 无口令：从 DataStore 的设备包装取得 DEK；设备包装不存在/失效 → `VLT_E_DEVICE_WRAP_MISSING` / `VLT_E_DEVICE_WRAP_AUTH`，不读取 `vaultCiphertext`。
6. **解密库 JSON**：`AESGCM(DEK).decrypt(vaultNonce, vaultCiphertext || vaultTag, aad=AAD_vault)`；**先验证 GCM 标签**。
7. **只有第 6 步成功后**，才对明文做 UTF-8 解码、JSON 解析与 schema 校验（`schemaVersion == 1`、字段类型/长度/上限）。
8. 解锁状态机进入 `Unlocked`；失败时保持 `Locked`/`PasswordPrompt`，不得写入任何文件。

关键点：

- **绝不**在 GCM 校验前把明文交给 JSON 解析器。
- **绝不**在解包 DEK 前使用 `vaultCiphertext`。
- 任何失败都不得覆盖现有 `vault.bin`；写入使用临时文件 + `fsync` + 原子 rename。
- 口令错误 vs 文件损坏：对用户统一提示（不泄露 oracle）；内部错误码可区分 `VLT_E_WRAP_AUTH` 与 `VLT_E_VAULT_AUTH`，但不得记录秘密。

---

## 5. 校验上限与错误码

| 错误码 | 触发条件 | 处理 |
|---|---|---|
| `VLT_E_FILE_TOO_LARGE` | 文件 > 12 MiB | 拒绝，不派生密钥 |
| `VLT_E_FILE_TRUNCATED` | 短于头部/声明长度 | 拒绝 |
| `VLT_E_TRAILING_DATA` | 多出尾随字节 | 拒绝 |
| `VLT_E_MAGIC` | 前 4 字节不是 `2FAV` | 拒绝 |
| `VLT_E_VERSION` | `formatVersion != 1` | 拒绝（提示升级） |
| `VLT_E_FLAGS` | 未知 flag 位，或 flags 与 KDF/包装字段不一致 | 拒绝 |
| `VLT_E_KDF_ALGO` | 有口令但 `kdfAlgo != 1`，或无口令但 `kdfAlgo != 0` | 拒绝 |
| `VLT_E_KDF_PARAMS` | KDF 参数越界 / `mKiB < 8*pLanes` / 无口令时非零 | 拒绝 |
| `VLT_E_WRAPPED_LEN` | `wrappedDekLen` 不是 0/32，或与 flags 不一致 | 拒绝 |
| `VLT_E_CT_LEN` | `ctLen == 0` 或 > 8 MiB | 拒绝 |
| `VLT_E_WRAP_AUTH` | KEK 解包 DEK 的 GCM 校验失败（口令错/头部/密文被改） | 停留口令输入 |
| `VLT_E_VAULT_AUTH` | 库 JSON 的 GCM 校验失败（DEK 错/密文/tag 被改） | 拒绝，保持锁定 |
| `VLT_E_JSON` | 明文不是合法 UTF-8 JSON | 拒绝（视为损坏） |
| `VLT_E_SCHEMA` | JSON 合法但根/版本/字段不符合 §5.2 | 拒绝（提示不兼容） |
| `VLT_E_DEVICE_WRAP_MISSING` | 无口令通道且 DataStore 无设备包装 | 提示无法解锁/需备份 |
| `VLT_E_DEVICE_WRAP_AUTH` | Keystore 解包失败/生物识别变更导致密钥失效 | 走口令通道（若有） |

错误码前缀 `VLT_` 表示库文件；`BAK_` 表示备份文件。两者不得混用。

### 5.1 库 JSON 校验

解密成功后按设计文档 §5.2 校验：

- 根必须是对象；`schemaVersion` 必须为 `1`；`items` 必须是数组。
- 每个 item 必填 `id/type/accountName/secretB64/algorithm/digits/period/sortIndex/createdAt/updatedAt`；`counter` 仅 HOTP；`algorithm` 只能是 `SHA1/SHA256/SHA512`；`digits` 6/8；`period` 1~300；secret 解码后 1~255 字节。
- 文本字段 NFC 规范化；长度上限按设计文档（issuer ≤ 128，accountName ≤ 256）。
- 未知字段忽略（向前兼容）；`schemaVersion > 1` 必须拒绝，不能降级写入。
- 建议限制 items 数量（如 ≤ 10000）以防内存放大；`ctLen` 已有 8 MiB 硬上限。

---

## 6. Worked example（有口令通道）

示例仅用于规范说明，**口令、DEK、KEK 都是公开测试值，绝对不能用于真实库**。

```text
password  = "correct horse battery staple"
mKiB      = 65536
tIter     = 3
pLanes    = 2
salt      = 000102030405060708090a0b0c0d0e0f
dekNonce  = 202122232425262728292a2b
vaultNonce= 303132333435363738393a3b
DEK       = 00112233445566778899aabbccddeeff102132435465768798a9bacbdcedfe0f
KEK       = c2b1508b0add7887a11a9a96f1a47225cb40bfa06e7570ff73a41418b2a15d61
```

派生与加密：

```text
KEK  = Argon2id(NFC(password), salt, 65536, 3, 2, 32)
wrap = AESGCM(KEK).encrypt(dekNonce, DEK, aad=bytes[0..63))
     -> wrappedDekCiphertext(32) || wrappedDekTag(16)
ctLen = 240
vault = AESGCM(DEK).encrypt(vaultNonce, vaultJSON, aad=bytes[0..115))
     -> vaultCiphertext(240) || vaultTag(16)
```

### 6.1 明文 JSON（240 字节）

```json
{"schemaVersion":1,"vaultId":"6f1c2a6e-6f4d-4c1b-9d4e-2a7f0f3f9b21","createdAt":"2026-11-04T10:00:00Z","updatedAt":"2026-11-04T10:00:00Z","settings":{"lockTimeoutSeconds":30,"hideCodesByDefault":false,"clipboardClearSeconds":30},"items":[]}
```

### 6.2 头部（115 字节）

```text
32464156010101000100000000000300000002000102030405060708090a0b0c
0d0e0f202122232425262728292a2b303132333435363738393a3b0000002002
7691bb1d7b0ea377ce4ca1404fe32f1e7bb57855539312491adb3ab28a23f291
cdb7364d68336662a106dd035a3765000000f0
```

字段拆解：

| 偏移 | 字节 | 值 |
|---:|---|---|
| `[0,4)` | `32 46 41 56` | `2FAV` |
| 4 | `01` | version 1 |
| 5 | `01` | flags：口令通道存在 |
| 6 | `01` | kdfAlgo：Argon2id v1.3 |
| `[7,11)` | `00010000` | 65536 |
| `[11,15)` | `00000003` | 3 |
| `[15,19)` | `00000002` | 2 |
| `[19,35)` | `000102...0e0f` | salt |
| `[35,47)` | `2021...2a2b` | dekNonce |
| `[47,59)` | `3031...3a3b` | vaultNonce |
| `[59,63)` | `00000020` | wrappedDekLen = 32 |
| 63..95 | `027691bb1d7b0ea377ce4ca1404fe32f1e7bb57855539312491adb3ab28a23f2` | wrappedDekCiphertext |
| 95..111 | `91cdb7364d68336662a106dd035a3765` | wrappedDekTag |
| `[111,115)` | `000000f0` | ctLen = 240 |

### 6.3 包装 DEK

```text
wrappedDekCiphertext = 027691bb1d7b0ea377ce4ca1404fe32f1e7bb57855539312491adb3ab28a23f2
wrappedDekTag        = 91cdb7364d68336662a106dd035a3765
```

### 6.4 库 JSON 密文与标签

```text
vaultCiphertext = ca43d68e41c9516ddb7a6f4faaf7c012086d807d388c6d56994562580665e388eb138df5c2e9bb811a3b6a256fa574f0aa577172ac960f8b8deb0d7bcf1249e4edf4f3c319c06f5c13e2d1137e8f9e9b86be6ad0f96cc9c97e406fe3914b968e04f93d1545c5d098c051738f387c85bfe96ecda1cb93cc88ef1d285cfbace4fceb98d8b2c60d6af2c4f73ca64b06282ccee3a07ce921084ac02519124b755c74f29879010d708513df6e133d5408c01b3519acc3a7e8bf2f31822e4aaccaf1e4a6d8818760a9c4e9dd74620a6dd004e3bca3d489099f7e0a33934d8cd5ea01b95a7fd1538f74e04456df360f65a71eee
vaultTag        = bab91d2271d1ac78fd62e743a645527f
```

### 6.5 完整文件

```text
fileLen = 115 + 240 + 16 = 371
sha256  = 15c3fe1a9b5099c3434152f768a117e694e0c9057d66e0edac27609001894a02
fileHex =
32464156010101000100000000000300000002000102030405060708090a0b0c0d0e0f202122232425262728292a2b30
3132333435363738393a3b00000020027691bb1d7b0ea377ce4ca1404fe32f1e7bb57855539312491adb3ab28a23f291
cdb7364d68336662a106dd035a3765000000f0ca43d68e41c9516ddb7a6f4faaf7c012086d807d388c6d569945625806
65e388eb138df5c2e9bb811a3b6a256fa574f0aa577172ac960f8b8deb0d7bcf1249e4edf4f3c319c06f5c13e2d1137e
8f9e9b86be6ad0f96cc9c97e406fe3914b968e04f93d1545c5d098c051738f387c85bfe96ecda1cb93cc88ef1d285cfb
ace4fceb98d8b2c60d6af2c4f73ca64b06282ccee3a07ce921084ac02519124b755c74f29879010d708513df6e133d54
08c01b3519acc3a7e8bf2f31822e4aaccaf1e4a6d8818760a9c4e9dd74620a6dd004e3bca3d489099f7e0a33934d8cd5
ea01b95a7fd1538f74e04456df360f65a71eeebab91d2271d1ac78fd62e743a645527f
```

解密校验：

1. 读头部，校验 `flags=1`、`wrappedDekLen=32`、`fileLen=371`。
2. 用口令和 salt 派生 KEK；解包 DEK（AAD 为前 63 字节）。
3. 用 DEK 解密库 JSON（AAD 为前 115 字节）。
4. 得到的明文必须逐字节等于 §6.1。

---

## 7. 设备包装

Android 使用应用私有 DataStore 保存包装模式、12 B IV、16 B salt、AES-256-GCM 密文及标签；各模式对应独立的不可导出 Android Keystore 密钥。AAD 为 UTF-8 `InkOTP/session/v1/<mode>`、一个零字节及当前头部 salt 拼接。

| Android 包装 | 库头部 | 包装材料 | 使用条件 |
|---|---|---|---|
| `device-bound` | 无恢复口令 | 32 B DEK | 手机已解锁。保留旧版模式。 |
| `device-local` | 有恢复口令 | 32 B DEK + 32 B KEK | 手机已解锁，每次进入验证关闭。 |
| `biometric` | 有恢复口令 | 32 B DEK + 32 B KEK | 只读取旧版包装。强生物识别成功后迁移到 `device-local`。 |
| `system-auth` | 有恢复口令 | 32 B DEK + 32 B KEK | 每次进入验证开启。API 30+ 使用系统身份验证绑定的密钥操作；API 26–29 由 Android 锁屏确认和限时密钥共同授权。 |

有恢复口令时，解开设备包装后还须用 KEK 对当前头部的密码包装做 GCM 校验，并以恒定时间比较得到的 DEK 与设备包装的 DEK。验证通过后才能打开库密文。升级时，旧版仅有恢复口令通道的库，须先成功用恢复口令解密，随后写入新的设备包装。

每次进入验证的布尔策略独立于包装读取，启用和停用时与新包装通过同一 DataStore 事务提交。策略开启时，缺失、无效或过期的系统包装不能触发本机免验证解锁；重新绑定须验证 Android 系统身份及应用恢复口令。修改口令和导入备份时使用新的包装，开启策略时须先获得新的 Android 系统授权。若库文件已成功提交但包装写入中断，旧包装无法通过当前头部和 DEK 验证；恢复口令仍可从已提交的库中恢复数据。

- 无口令通道时，设备包装丢失且没有备份将无法恢复数据。导出备份前须先设置恢复口令。
- 本地库文件和平台包装必须排除系统自动备份与云同步。
- Android 锁定救援替换已有库前，原始加密文件的字节保存在应用私有目录的单一 `vault.pre-restore.bin` 副本中。此副本采用原密文格式、同属应用私有生命周期，不纳入系统备份。原库无法保全时终止替换。

---

## 8. 平台中立性

- Android 与独立参考实现使用同一 `2FAV` v1 公共样本。等价的 Argon2id 与 AES-256-GCM 实现必须保持相同的字节序、参数与 AAD。
- 新增字段必须提升 `formatVersion`。实现须拒绝未知版本。
- 设备包装格式和应用私有文件的外层结构遵循各平台的本地契约。

---

## 9. 安全清单

- [ ] `vault.bin` 位于应用私有目录，排除系统备份。
- [ ] 每次写库使用新的 `vaultNonce`；每次重新包装 DEK 使用新的 `dekNonce`；禁止复用。
- [ ] 解密先校验结构，再派生 KEK，再解包 DEK，再 GCM 验证，最后才解析 JSON。
- [ ] 失败不落盘、不覆盖旧库、不产生半成品。
- [ ] 不记录 DEK/KEK/secret/验证码/明文。
- [ ] 口令通道与设备通道都失败时，UI 明确说明"数据无法恢复"，不尝试猜测。
- [ ] 测试 fixture 的固定 nonce/DEK 只用于规范示例，生产代码不得使用。

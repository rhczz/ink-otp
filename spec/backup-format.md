# 备份文件格式 v1（`.2fab`）

> 状态：**历史归档**。自 InkOTP 0.6 起移除 `.2fab` 读写。当前支持的备份见 [交换规格](interoperability.md)，旧工具可从 [历史发布源码](https://github.com/rhczz/ink-otp/tree/v0.5.4-beta.1/tools) 查阅。
> 适用范围：历史 InkOTP 版本生成的 `.2fab` v1 备份。
> 设计依据：`docs/design/2FA验证器-技术设计文档.md` §7.2 / §7.3，并做出一处明确修正（见下）。

## 1. 目标与边界

`.2fab` 是**可移植的加密备份**：只依赖用户恢复口令，不依赖任何设备密钥；换机、重装、丢机后都能恢复。

设计约束：

1. **可移植**：备份中不含 平台特有字段、设备 ID、账号 ID 或任何用户标识。
2. **可演进**：版本、KDF 算法与参数、AEAD 参数都显式写在文件头。
3. **抗篡改**：文件头是 AEAD 的 AAD；任何头部字节被改动都会在解密时失败。
4. **可审计**：固定 50 字节头部 + 密文 + 16 字节标签，没有嵌套容器。
5. **先验证后解析**：GCM 校验通过前，绝不把明文交给 JSON/schema 解析器。
6. **失败不留半成品**：导入失败不修改本地库；成功时用临时文件 + 原子替换。

### 1.1 与设计文档的一处明确修正

设计文档 §6.1 描述了 DEK 层级，§7.2 的备份布局则是：

```text
ciphertext = AES-256-GCM(KEK, vault JSON)
```

**v1 备份采用 §7.2：备份密文直接用口令派生的 KEK 加密，备份中不包含 DEK，也不包含 DEK 包装。**
DEK 只用于设备上的 `vault.bin`（见 `spec/vault-format.md`）；备份导出时把库 JSON 当作独立明文重新加密。
这一修正是有意的：备份必须能被任何设备仅凭口令解开，不能把设备侧 DEK 包装带进备份。

### 1.2 文件命名与 MIME

| 项 | 值 |
|---|---|
| 扩展名 | `.2fab` |
| 建议文件名 | `inkotp-backup-YYYYMMDD.2fab`（文件名不参与格式） |
| MIME | `application/octet-stream` |
| 最大文件 | 12 MiB（导入硬上限） |
| 最大 `ctLen` | 8 MiB |

文件名、文件系统时间戳、扩展属性都不属于格式，导入时忽略。

---

## 2. 字节布局

所有整数均为**大端序（big-endian）**；所有长度单位为字节。

| 偏移 | 长度 | 字段 | 类型 | 说明 |
|---:|---:|---|---|---|
| 0 | 4 | `magic` | bytes | ASCII `2FAB`（`32 46 41 42`） |
| 4 | 1 | `formatVersion` | u8 | 固定 `0x01` |
| 5 | 1 | `kdfAlgo` | u8 | `1` = Argon2id v1.3（version `0x13`） |
| 6 | 4 | `mKiB` | u32 BE | Argon2 内存成本，KiB |
| 10 | 4 | `tIter` | u32 BE | Argon2 迭代次数 |
| 14 | 4 | `pLanes` | u32 BE | Argon2 并行度 |
| 18 | 16 | `salt` | bytes | 随机盐 |
| 34 | 12 | `nonce` | bytes | AES-256-GCM nonce，每文件唯一 |
| 46 | 4 | `ctLen` | u32 BE | 密文字节数，等于明文 JSON 字节数 |
| 50 | `ctLen` | `ciphertext` | bytes | `AES-256-GCM(KEK, vault JSON)` 的密文部分 |
| 50+`ctLen` | 16 | `tag` | bytes | GCM 认证标签 |

恒等式：

```text
fileLen = 50 + ctLen + 16
```

`ctLen` 必须大于 0 且不超过 8 MiB；文件必须**恰好**等于上述长度，不允许多余尾随字节。

### 2.1 AAD 与 AEAD

| 项 | 约定 |
|---|---|
| 算法 | AES-256-GCM |
| 密钥 | `KEK`，32 字节，由口令经 Argon2id 派生 |
| nonce | 头部 `nonce` 字段，12 字节；每个文件/每次导出必须重新随机生成 |
| 明文 | 库 JSON 的 UTF-8 字节（对象，`schemaVersion = 1`） |
| AAD | **头部 `bytes[0..50)`**，即从 `magic` 到 `ctLen` 的全部 50 字节 |
| 密文 | AES-GCM 输出的前 `ctLen` 字节 |
| 标签 | AES-GCM 输出的最后 16 字节，单独存放 |
| 解密 | 先按头部重新计算 AAD，再 `AESGCM(KEK).decrypt(nonce, ciphertext || tag, aad=header)` |

注意：

- `tag` 本身不是 AAD 的一部分；GCM 的标签覆盖 AAD 与密文。
- 头部中的 `salt`、`nonce`、`ctLen` 都被认证；篡改任意一项都会导致校验失败。
- 不做"先解密、后校验"的流式解析；不允许把未验证的明文写入磁盘。
- nonce 复用会灾难性地破坏 GCM 安全性。导出必须使用 CSPRNG，禁止固定 nonce（测试 fixture 的固定 nonce 仅用于复现，见 §8）。

---

## 3. KDF 参数

### 3.1 算法

**Argon2id v1.3**（Argon2 version `0x13` = 19），低层 API 显式指定 `m` / `t` / `p`，输出 32 字节 KEK。

| 参数 | v1 导出固定值 | 导入接受范围 | 说明 |
|---|---:|---:|---|
| `mKiB` | 65536 | 8192 ~ 524288 | 64 MiB；上限 512 MiB 防内存 DoS |
| `tIter` | 3 | 1 ~ 10 | 迭代次数 |
| `pLanes` | 2 | 1 ~ 8 | 并行度 |
| `salt` | 16 B 随机 | 固定 16 B | CSPRNG |
| 输出 | 32 B | 固定 32 B | KEK |
| 约束 | — | `mKiB >= 8 * pLanes` | Argon2 库要求 |

导入端接受上述范围而不是只接受 65536/3/2，是为了让未来 v1 文件在提高参数后仍可读；**v1 导出端仍必须写 65536/3/2**。任何越界参数在派生密钥前拒绝。

### 3.2 口令处理

- 口令先做 **Unicode NFC 规范化**，再编码为 UTF-8；不 trim、不改变大小写、不做其他变换。
- 双端必须使用同一规范化，否则同一视觉口令会得到不同 KEK。
- 导出时口令至少 8 个字符（推荐 ≥ 12），拒绝纯数字与常见弱口令；具体 UI 策略见设计文档 §7.3。
- 不做口令提示问题；不把口令写入文件、日志、崩溃信息。
- 口令遗忘 = 数据永久丢失，UI 必须明确声明并要求确认。

### 3.3 参数写入文件头的理由

备份可能在多年后恢复。把 KDF 参数写入头部后，未来可以整体提高默认参数而不破坏旧文件；旧 App 打开新参数文件时，要么按范围接受，要么明确提示"备份参数不受支持"。

---

## 4. 导入校验上限与失败语义

### 4.1 校验顺序（必须按此顺序，避免对畸形文件做昂贵计算）

1. 文件长度上限（12 MiB）。
2. 至少 50 字节头部。
3. `magic`、`formatVersion`、`kdfAlgo`。
4. KDF 参数范围与 `mKiB >= 8 * pLanes`。
5. `ctLen` 范围。
6. 精确文件长度：`50 + ctLen + 16`，不允许多余字节。
7. 派生 KEK（仅在此之后）。
8. GCM 解密并验证标签。
9. UTF-8 解码与 JSON 解析。
10. 库 schema 校验（`schemaVersion == 1`，字段类型/上限）。
11. 预览、合并/替换、原子写入。

### 4.2 错误码

| 错误码 | 触发条件 | 用户可见语义 |
|---|---|---|
| `BAK_E_FILE_TOO_LARGE` | 文件 > 12 MiB | 文件不完整或已损坏 |
| `BAK_E_FILE_TRUNCATED` | 文件短于头部，或短于声明的 `50+ctLen+16` | 文件不完整或已损坏 |
| `BAK_E_TRAILING_DATA` | 文件多于声明的长度 | 文件不完整或已损坏 |
| `BAK_E_MAGIC` | 前 4 字节不是 `2FAB` | 这不是本应用的备份文件 |
| `BAK_E_VERSION` | `formatVersion != 1` | 备份来自更新版本，请升级 App |
| `BAK_E_KDF_ALGO` | `kdfAlgo != 1` | 备份参数不受支持 |
| `BAK_E_KDF_PARAMS` | `mKiB/tIter/pLanes` 越界或 `mKiB < 8*pLanes` | 备份参数不受支持 |
| `BAK_E_CT_LEN` | `ctLen == 0` 或 `ctLen > 8 MiB` | 文件不完整或已损坏 |
| `BAK_E_AUTH` | 口令错误、密文/标签被改、头部被改导致 GCM 校验失败 | 口令不正确（不区分原因） |
| `BAK_E_JSON` | GCM 通过，但明文不是合法 UTF-8 JSON | 文件不完整或已损坏 |
| `BAK_E_SCHEMA` | JSON 合法，但根不是对象、`schemaVersion != 1`、`items` 不是数组等 | 备份来自不兼容版本 |

失败语义：

- **口令错误与篡改统一为 `BAK_E_AUTH`**：不向用户区分"口令错"还是"文件被改"，避免 oracle。UI 统一提示"口令不正确"，但内部日志可记录错误码（不含任何秘密）。
- 头部格式错误必须在 Argon2 之前失败；不得为了"诊断"而对畸形文件派生密钥。
- 认证失败时不得写入任何本地数据，不得保留解密出的部分明文。
- 导入成功也必须先在临时文件中完成重建，再原子替换；进程被杀时旧库保持完整。
- `BAK_E_JSON` / `BAK_E_SCHEMA` 发生在认证成功之后；仍不得写入本地库，且应提示备份来自不兼容/损坏的来源。

---

## 5. 导出流程（规范）

1. 校验库已解锁且口令通道已设置；未设置则先要求设置口令。
2. 读取/确认 KDF 参数（v1 固定 65536/3/2）；用 CSPRNG 生成 16 B `salt` 与 12 B `nonce`。
3. `KEK = Argon2id(NFC(password), salt, m, t, p, 32)`。
4. 序列化库 JSON（UTF-8）；`ctLen = len(json)`。
5. 构造 50 字节头部；`AESGCM(KEK).encrypt(nonce, json, aad=header)`，拆分密文与 16 B 标签。
6. 按 §2 拼接，写入临时文件；`fsync` 后原子重命名。
7. 交给系统分享/文件导出；提示"备份等同于钥匙，请放在可信位置，不要发到聊天工具"。
8. 立即从内存中清除 KEK 与明文引用（托管语言尽力而为）。

导出文件**不包含**：设备包装、Keystore/Keychain 信息、设备 ID、账号 ID、当前验证码、日志。

---

## 6. 导入流程（规范）

1. 选择文件；先做 §4.1 的 1~6 步（不派生密钥）。
2. 输入口令；派生 KEK（显示进度，可取消）。
3. GCM 校验并解密；失败 → `BAK_E_AUTH`，停留输入界面。
4. 解析 JSON 并校验 schema；失败 → `BAK_E_JSON` / `BAK_E_SCHEMA`。
5. 预览：账号数量、issuer 列表、创建时间。
6. 用户选择**合并**或**替换**；合并去重键 = `(type, issuer.lowercased(), accountName.lowercased(), SHA256(secret))`。
7. 原子写入本地库；重建通道 A 的设备包装；提示重新开启生物识别。
8. 清除明文与 KEK 引用。

---

## 7. 互操作与平台中立性

- 本规范**平台中立**：字段、整数编码、KDF、AEAD 均不依赖 Android。
- 不允许在头部追加"仅 Android 可用"的字段。新增字段必须提升 `formatVersion`，且旧实现必须拒绝而不是忽略未知版本。
- `.2fab` 与 `vault.bin` 是两种不同格式：前者是口令可移植备份，后者是设备本地加密库；不要把设备包装混入备份。

---

## 8. 已提交的测试 fixture

`spec/fixtures/` 中的历史文件由旧版 `tools/reference_backup.py --create-fixture` 生成，使用固定盐和 nonce。重建时须逐字节一致。[历史生成器](https://github.com/rhczz/ink-otp/blob/v0.5.4-beta.1/tools/reference_backup.py)

| 文件 | 说明 | SHA-256 |
|---|---|---|
| `backup-v1.2fab` | 1476 字节的合法 v1 备份 | `8f68c9679df531f9e73c36cafe5a84019654d51f775081939b70634f36c040b1` |
| `backup-v1.expected.json` | 解密后的明文，1410 字节 | `d400ad29e91ac39f21d35316942b2bda30db42f0c8634d2092954d3bc0189297` |
| `backup-v1.meta.json` | 口令、KDF 参数、盐/nonce、哈希 | — |

fixture 参数：

```text
password = "correct horse battery staple"
mKiB     = 65536
tIter    = 3
pLanes   = 2
salt     = 000102030405060708090a0b0c0d0e0f
nonce    = 101112131415161718191a1b
ctLen    = 1410
tag      = f92dd4c700b7fbc43273a53544937c03
```

> 固定盐/nonce **只用于可复现的测试 fixture**。生产导出必须每次随机生成，绝不能复用。

验证命令：

```bash
tools/.venv/bin/python tools/reference_backup.py --inspect spec/fixtures/backup-v1.2fab
tools/.venv/bin/python tools/reference_backup.py --decrypt spec/fixtures/backup-v1.2fab \
  --password 'correct horse battery staple'
tools/.venv/bin/python tools/crosscheck.py --spec spec --backup-dir /tmp/m0-empty
```

---

## 9. 完整 worked example

以下示例与 `spec/fixtures/backup-v1.2fab` 完全一致。文件由三段拼接：

```text
header (50 B) || ciphertext (1410 B) || tag (16 B)
```

### 9.1 头部（50 字节）

| 字段 | 值 |
|---|---|
| `magic` | `2FAB` |
| `formatVersion` | `01` |
| `kdfAlgo` | `01` |
| `mKiB` | `00010000`（65536） |
| `tIter` | `00000003` |
| `pLanes` | `00000002` |
| `salt` | `000102030405060708090a0b0c0d0e0f` |
| `nonce` | `101112131415161718191a1b` |
| `ctLen` | `00000582`（1410） |

完整头部十六进制：

```text
324641420101000100000000000300000002000102030405060708090a0b0c0d
0e0f101112131415161718191a1b00000582
```

### 9.2 密文（1410 字节）

```text
7b8dd996347928dcf7f908f314217408a8765719b8e1fd36f251c8ca947bdc80da49597c79c253686314483ff3fc8eea
3ef0a789ce221d04840b032070110f429b0176556b4cad9332c11ef1385c52f609206844d67046078bba3f44158b3c1e
9fbf6b57ea1921affe69fdc58ede970215cd35d08a0f86fc60c39e642a96dd4652460a163738a5535c82080431f7b6c5
ebd8e29a163ddb1dd19a2cb8cd4c28182bbd873041b8e5d181f38d88cb816b5ecd86aed602e779fb1b58bfe1121d0222
0c684dfa559cbe0e0031589a997929b2f3e8bde59bbef9590a6d1d2362c07d5749b453c393a2b2f2b447a76d39b515d5
5e3835e20d73224a325568c6329af87cd011e651403e94994da677280bd06f8fe95812b19af97534c182e5631119ecbc
cf527499dfd4cdbe04dff5d959a108fa0891045ca3635aa0e18eb8b240a0b731dbdd912c0ae52d79e1dc52e6c5c5d670
218e9ff6ed1bd9c84f86762037746661a0fb2316b239922d70ac53951679ff2f7b956d476bf11951e3fcc320c9005281
5a763d344c9c53f212ef984fe6264f6d557c7b50f94545f86d6ceaa4e704220ba3dee744a874aadba884aa86c896fc85
504cc91ae00578592c45dcf850c54677c5d7dd62e6cd99f22dab73abf334a07414f56a828c958770303bc9dfbaf544d8
38a0f4f569c265fc56ce11895cc9d26920ba7bb8874dea5446015212ac74a3944adfc0405ab943c17f09970af4efe71a
b3021c6870b18fbc5f9205d6e60f2e32158aaa110c77d3fd8ae7adfe45290ed66572d53e8a9f0399c5debbb62a318f9b
7f1561d8a9fe5677c2765e7b3166b2c995cc6232e679c6c45e4000bb699f6a576b958709391bbd7900e11c83b8ea20b4
44a485d8838b7ad6410fcda43cca5c07099a4fd47fcbcea2b38811839d068d9d66e02e4e968c5b70709126cdfb20a643
d1654599a4584dfe6ef53f8a0ca99d77788f60c810605c28a94a7104cfa6e601ee1709db5b74b99e2da90c6a7f54e823
099c858f92c6a1f6f2fe2b5781b627f61a1e454cb53669c182b694de6750782fef491927ebfdf96e06f15ae4f69ea24d
6a55193e732386baf7a637cd2368d21e6be71dd05e0e568602e3728306852fbc9105b7baced8e0a76fbf3453e09dc8fa
dd9b3d2542a6b7b1151c160e3c3425021cfccf349f94bcd67071623482c064b6a35de18f3fec8109307c706cdbe68068
e95b37a1bdfd19a6a599b0f9fe477488d80f15e2a69238432aff12c034c0eb699e18811db9707900d22e958ec613ac51
a1830d57f192f49ef6e220987b64b9e2cc8e61b9e61ea66a34249275d24650203ae42fdad34bece4efa4a9fe22eaccbc
e91d57bf712eec1537b52e6ba9f5c4f5203a0651585ca24bee4007c75b8402e67ce4e67a294d67330e1b209e9f68767e
00b52ff730e237a09541c81128185b1d3fff219e9e6b3ff1ded116e7e4e775932036de118f91f1ee8616708d75ddfd65
92b2cdbb32a3f39c3cfc9af664e00022cb46f78c7562361daaf5851f432a21ff4ba9c203a39e081e6761e0ac8467910d
554d84773a7f5ed69a69e7342d896c9c4afe8f657957b4a0b11446a16ccf36d1eaca22ac3799509d4f94aeab72c28f44
2370c27157bf788bb46d01b2644548807a781bec25959651c9ee2a46e4c5dfb61b6fdb3b6eb9b34310e21923672abfff
bd8b215f1e8d4d6753e9616620ca3fffb9efc7c7e9ad1f30a4f66c5af2ca8f816957a91b6040f8a98cb4dad2818753ee
a1f79ece7b75ae06be55aab971e6c4270386fad031f1a1c4922375ab3a9e8cda083f159e16d916293d0ff9127398e896
c5383f1963c40f04085ae59daf7289605614f0386ed0de36a5f0b9c4d3477c9a9ad7ea9a6919ce61d2f8f5ed8c8240e3
bccd083611403c410f5baab5c9efaba5d3c0027ed4fc42c3ab032e6761f8028c21e2964af7050b430183a5eec7b5aa56
7d40cc07c6c563b19d0a1b2b2e1268e9cd55
```

### 9.3 标签（16 字节）

```text
f92dd4c700b7fbc43273a53544937c03
```

### 9.4 明文 JSON（UTF-8，1410 字节，文件末尾含一个换行符）

```json
{
  "schemaVersion": 1,
  "vaultId": "6f1c2a6e-6f4d-4c1b-9d4e-2a7f0f3f9b21",
  "createdAt": "2026-11-04T10:00:00Z",
  "updatedAt": "2026-11-04T10:05:12Z",
  "settings": {
    "lockTimeoutSeconds": 30,
    "hideCodesByDefault": false,
    "clipboardClearSeconds": 30
  },
  "items": [
    {
      "id": "0d9a3c1e-8f21-4d6a-9c3e-1234567890ab",
      "type": "totp",
      "issuer": "GitHub",
      "accountName": "me@example.com",
      "secretB64": "MTIzNDU2Nzg5MDEyMzQ1Njc4OTA=",
      "algorithm": "SHA1",
      "digits": 6,
      "period": 30,
      "counter": null,
      "tags": [],
      "icon": {
        "kind": "letter",
        "value": "G",
        "colorHex": "#24292F"
      },
      "sortIndex": 1000,
      "origin": "qr",
      "createdAt": "2026-11-04T10:03:00Z",
      "updatedAt": "2026-11-04T10:03:00Z"
    },
    {
      "id": "7c4b2a10-3d5e-4f6a-8b9c-0d1e2f3a4b5c",
      "type": "totp",
      "issuer": "Example Corp",
      "accountName": "alice@example.com",
      "secretB64": "MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI=",
      "algorithm": "SHA256",
      "digits": 8,
      "period": 60,
      "counter": null,
      "tags": [],
      "icon": {
        "kind": "letter",
        "value": "E",
        "colorHex": "#1F6FEB"
      },
      "sortIndex": 2000,
      "origin": "manual",
      "createdAt": "2026-11-04T10:04:00Z",
      "updatedAt": "2026-11-04T10:04:00Z"
    }
  ]
}
```

### 9.5 验证

对上述头部重新计算 `AESGCM(KEK).decrypt(nonce, ciphertext || tag, aad=header)`，应得到 §9.4 的字节。`KEK` 由口令 `correct horse battery staple` 与 §8 的参数/盐派生。`tools/reference_backup.py --decrypt` 已逐字节验证。

---

## 10. 安全清单

- [ ] 不在日志/崩溃信息中输出口令、KEK、明文、secret、URI。
- [ ] 不在备份中放用户标识或设备标识。
- [ ] 每次导出使用新的 CSPRNG `salt` + `nonce`。
- [ ] 导入先校验头部，再派生密钥；KDF 参数有上限。
- [ ] GCM 校验通过前不解析 JSON。
- [ ] 失败不落盘、不产生半成品库。
- [ ] 备份文件不进入系统自动备份/云同步（由 App 排除规则保证）。
- [ ] 测试向量与 fixture 不含真实用户 secret。

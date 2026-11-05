# `tools/` — Python 参考实现与检查脚本

这些脚本只在开发/CI 使用，**不随 App 发布**。它们是独立的第二实现：如果 Kotlin 与本文档/脚本不一致，以 RFC 向量和 `spec/` 为准。

| 脚本 | 作用 |
|---|---|
| `reference_totp.py` | Base32 / HOTP / TOTP 独立实现；RFC 自测；生成 `spec/vectors.json` |
| `reference_backup.py` | `.2fab` 独立实现；自测；生成 fixture；解密/检查备份 |
| `crosscheck.py` | 交叉检查 vectors、fixture、可选 Kotlin 备份 |
| `check_no_internet.py` | 检查 merged manifest 是否申请网络/存储权限 |
| `check_design_tokens.py` | 扫描 Kotlin 里的硬编码颜色/字号/圆角 |
| `fetch_material_symbols.py` | 开发期拉取 Material Symbols SVG 并生成 Android vector（生成物已提交） |

依赖只有两个，版本已锁定：

```text
argon2-cffi==25.1.0
cryptography==50.0.2
```

Python 版本：**3.15**（CI 与本机一致）。

### 图标资产

```bash
python3 tools/fetch_material_symbols.py
```

从 Google Fonts 拉取 Material Symbols Rounded 的 24px SVG，转换到
`android/app/src/main/res/drawable/ic_*.xml`。只在需要新增/更新图标时运行；
App 构建不联网。

---

## 1. 环境

```bash
make setup
# 等价于：
python3 -m venv tools/.venv
tools/.venv/bin/pip install -r tools/requirements.txt
```

`tools/.venv/` 已被 `.gitignore` 忽略，不要提交。日常运行用 `tools/.venv/bin/python`，避免污染系统 Python。

---

## 2. OTP 参考实现

### 2.1 自测

```bash
tools/.venv/bin/python tools/reference_totp.py --selftest
```

输出示例：

```text
RFC 4226/6238 vectors ................ 28/28 (10 HOTP + 18 TOTP)
Base32 RFC 4648 + tolerant/invalid ... 162/162
Domain limits ........................ 12/12
vectors.json ......................... 141/141  .../spec/vectors.json (72 random)
SELFTEST PASS (343 checks)
```

`--selftest` 同时读取 `spec/vectors.json`，逐条重算 TOTP/HOTP、Base32、boundary、random；任何不一致都会非 0 退出。文件不存在时打印 `SKIPPED` 并只跑 RFC/域限制检查。

### 2.2 生成向量

```bash
tools/.venv/bin/python tools/reference_totp.py --generate-vectors spec/vectors.json
```

- 固定 seed `20261105`，同一 Python 版本输出逐字节稳定。
- 生成前会先断言 RFC 4226/6238 结果与公开表一致；不一致直接失败，不写文件。
- 字段与覆盖范围见 `spec/README.md` §2。
- 生成后请跑 `--selftest` 与 `crosscheck`。

### 2.3 约定

- HOTP counter 按 **u64 大端** 8 字节编码。
- TOTP 时间上限 `2^63-1`，避免 Java/Kotlin signed `Long` 溢出。
- Base32 解码容错：大小写、ASCII 空白、`-`、省略填充；填充存在时必须规范，末尾位必须为 0。
- 不 import Kotlin 代码，不依赖第三方库。

---

## 3. `.2fab` 参考实现

### 3.1 自测

```bash
tools/.venv/bin/python tools/reference_backup.py --selftest
```

覆盖：

- 默认参数（65536/3/2）与最低参数（8192/1/1）的 round-trip；
- magic / version / kdfAlgo / KDF 参数 / ctLen / 截断 / 尾随字节 / 超大文件；
- 错误口令、密文翻转、tag 翻转、salt/nonce 翻转；
- 非法 JSON、不支持的 `schemaVersion`。

输出：

```text
[ok] round-trip AES-256-GCM / Argon2id default parameters
...
SELFTEST PASS (15/15)
```

### 3.2 生成 fixture

```bash
tools/.venv/bin/python tools/reference_backup.py --create-fixture
```

写入：

```text
spec/fixtures/backup-v1.2fab            # 1476 B
spec/fixtures/backup-v1.expected.json   # 1410 B（明文）
spec/fixtures/backup-v1.meta.json       # 口令/KDF/salt/nonce/哈希
```

固定 salt/nonce，因此可复现；生产导出使用 CSPRNG。重新生成后哈希应保持不变；若改变明文/参数，需要同步更新 `spec/README.md` 中的哈希表。

### 3.3 解密 / 检查

```bash
tools/.venv/bin/python tools/reference_backup.py --inspect spec/fixtures/backup-v1.2fab
tools/.venv/bin/python tools/reference_backup.py --decrypt spec/fixtures/backup-v1.2fab \
  --password 'correct horse battery staple'
```

- `--inspect` 只打印头部字段，不派生密钥、不解密。
- `--decrypt` 打印解密后的 JSON（原样）；失败时 stderr 输出 `BAK_E_*` 并以 2 退出。
- 口令通过命令行参数传入，只用于本地交叉检查；不要把它写进脚本或日志。

格式细节、KDF 参数、AAD、错误码见 `spec/backup-format.md`。

---

## 4. 交叉检查

```bash
tools/.venv/bin/python tools/crosscheck.py --spec spec --backup-dir /tmp/m0-empty
```

检查：

1. `spec/vectors.json`：18 TOTP / 10 HOTP / Base32 / 12 boundary / 72 random，逐条重算；校验 RFC 表完整性、随机覆盖。
2. `spec/otpauth-cases.json`：结构、唯一 name、accept/error 一致性。
3. `spec/fixtures/backup-v1.2fab`：文件哈希、meta、KDF 参数、GCM 解密、明文逐字节比较、2 个条目 profile。
4. `<backup-dir>/kotlin-backup.2fab`（可选）：用文档口令（或 `kotlin-backup.meta.json` 的 password）解密，与 `kotlin-backup.expected.json` 做语义 JSON 比较。

示例 PASS：

```text
[PASS] vectors.json: 18 TOTP, 10 HOTP, 29 Base32, 12 boundary, 72 random
[PASS] otpauth-cases.json: 40 parser cases (19 accepted)
[PASS] fixture backup-v1.2fab: 1476 bytes, 2 items, sha256 8f68c9679df531f9...
[PASS] Kotlin backup: ... not found (skipped)
CROSSCHECK PASS
```

- Kotlin 文件不存在 → SKIP，不失败（M0 正常）。
- 任何已存在的文件不匹配 → 退出 1。
- 退出码：0 PASS，1 校验失败。

Kotlin 侧建议在 `:core` 的测试任务后把 `kotlin-backup.2fab`、`kotlin-backup.expected.json` 写到 `android/core/build/crosscheck/`，再跑 `make crosscheck`。

---

## 5. 无网络权限检查

```bash
tools/.venv/bin/python tools/check_no_internet.py --manifest \
  android/app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml
```

规则：

- 所有 `uses-permission*`（含 `uses-permission-sdk-23`）都会被读取。
- 禁止 `android.permission.INTERNET`、`android.permission.ACCESS_NETWORK_STATE`。
- 防御性禁止 `android.permission.READ_*`、`WRITE_*`、`MANAGE_*`（存储/媒体/联系人等宽权限；2FA App 不需要）。
- 打印完整权限列表，再打印违规项。

退出码：

| 退出码 | 含义 |
|---:|---|
| 0 | PASS：无违规权限 |
| 1 | FAIL：存在违规权限 |
| 2 | 文件不存在或 XML 解析失败 |

> 只依赖标准库 `xml.etree.ElementTree`。合并 manifest 的路径随 AGP 版本可能变化；用构建产物里的实际路径，或从 `aapt dump permissions` 复核。

---

## 6. 设计 token 扫描

```bash
tools/.venv/bin/python tools/check_design_tokens.py \
  --root android/app/src/main/kotlin
```

规则：

- `Color(0x......)` / `Color(0xFF......)`：只允许出现在 `ui/theme/AppTokens.kt`。
- `fontSize = <number>.sp`：只允许出现在 `ui/theme/` 包内。
- `RoundedCornerShape(<number>.dp)`：只允许出现在 `ui/theme/` 包内。
- `--allow <glob>` 可重复/逗号分隔，放行特定文件（如品牌色常量文件）。
- 输出 `file:line: reason`；退出码 0 干净 / 1 有违规 / 2 参数错误。
- 目录不存在（M0 早期树）→ 打印 warning、退出 0，避免阻塞骨架阶段。
- 扫描器是**行级启发式**，不是 Kotlin 解析器；注释行（`//`、`*`、`/*` 开头）跳过，字符串/内联注释中的字面量仍可能被匹配。它是防回归门，不替代设计评审。

示例：

```text
/tmp/inkotp-tokens/feature/Foo.kt:2: hard-coded Color(0x...) outside ui/theme/AppTokens.kt
/tmp/inkotp-tokens/feature/Foo.kt:3: hard-coded fontSize = <number>.sp outside ui/theme
FAIL: 2 violation(s) in 1 file(s)
```

---

## 7. 与 Makefile / CI 的衔接

```bash
make setup       # venv + pip
make spec        # reference_totp --selftest + reference_backup --selftest
make test        # Kotlin 单测（消费 spec/vectors.json）
make build
make crosscheck  # 构建后跑 crosscheck（可选 Kotlin 备份）
make verify      # test + build + lint + 权限/设计 token/Python 自测
```

CI 建议顺序：

1. `make setup`
2. `make spec`
3. `python3 -m json.tool spec/vectors.json` / `otpauth-cases.json`
4. `make test`
5. `make build`
6. `check_no_internet`（对合并 manifest）
7. `check_design_tokens`
8. `crosscheck`（有 Kotlin 备份时）

---

## 8. 安全与维护

- **不要**把真实 secret、真实备份、真实口令提交到仓库或 fixture。
- fixture 的固定 salt/nonce/DEK 只是测试值；生产使用 CSPRNG，且 nonce 绝不复用。
- 不要为了"让测试过"修改参考实现或手改向量；先确认 RFC/规范，再同步生成器、Kotlin 与文档。
- 依赖升级：同时更新 `requirements.txt`、本 README 的版本、`make setup` 后的自测结果；Argon2/AES-GCM 行为变化必须重跑 fixture 交叉检查。
- `tools/.venv`、`__pycache__` 不提交。

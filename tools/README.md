# `tools/` — Python 参考实现与检查脚本

这些脚本只在开发/CI 使用，**不随 App 发布**。Python 参考实现用于独立验证 Android 的 Kotlin 实现。如果两者结果不同，以 RFC 向量和 `spec/` 为准。

| 脚本 | 作用 |
|---|---|
| `reference_totp.py` | Base32 / HOTP / TOTP 独立实现；RFC 自测；生成 `spec/vectors.json` |
| `reference_interchange.py` | 独立验证上游样本及 Android 生成的 Aegis、2FAS 与 otpauth 文件 |
| `generate_google_transfer_fixture.py` | 生成、复核公共 Google Authenticator 迁移样本 |
| `crosscheck.py` | 核对 OTP、URI 和本地库公开样本 |
| `release_artifacts.py` | 验证构建证据、Android APK、版本及发布资产 |
| `check_no_internet.py` | 检查 merged manifest 是否申请网络/存储权限 |
| `check_design_tokens.py` | 扫描 Kotlin 里的硬编码颜色/字号/圆角 |
| `fetch_material_symbols.py` | 开发期拉取 Material Symbols SVG 并生成 Android vector（生成物已提交） |

依赖只有两个，版本已锁定：

```text
argon2-cffi==25.1.0
cryptography==50.0.2
```

GitHub Actions 使用 Python 3.13，本机验证使用 Python 3.15。

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

## 3. Google Authenticator 迁移样本

```bash
python3 tools/generate_google_transfer_fixture.py
python3 tools/generate_google_transfer_fixture.py --write
```

默认核对版本库中的样本。变更样本时由 `--write` 重新生成。输入只使用 RFC 4226 公开测试密钥，涵盖双账号单码、乱序多码、UTF-8、缺省字段和损坏、越界及不支持的输入。

## 4. 交叉检查

```bash
tools/.venv/bin/python tools/crosscheck.py --spec spec
tools/.venv/bin/python tools/reference_interchange.py --exports android/core/build/interoperability
```

`crosscheck` 独立重算 OTP 向量、核对 URI 用例结构，并用 Argon2id 和 AES-GCM 打开规范中的 `2FAV` 公共库。交换检查核对 10 份固定上游文件及实际写出的五种交换文件，逐项验证服务、账户和验证码参数。

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
make spec        # OTP 自测和 Google 迁移样本校验
make test        # Kotlin 单测（消费 spec/vectors.json）
make build
make crosscheck  # 构建后对拍实际标准交换文件
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
8. `crosscheck` 与 `reference_interchange`

---

## 8. 安全与维护

- **不要**把真实 secret、真实备份、真实口令提交到仓库或 fixture。
- fixture 的固定 salt/nonce/DEK 只是测试值；生产使用 CSPRNG，且 nonce 绝不复用。
- 不要为了"让测试过"修改参考实现或手改向量；先确认 RFC/规范，再同步生成器、Kotlin 与文档。
- 依赖升级：同时更新 `requirements.txt`、本 README 的版本、`make setup` 后的自测结果；Argon2/AES-GCM 行为变化必须重跑 fixture 交叉检查。
- `tools/.venv`、`__pycache__` 不提交。

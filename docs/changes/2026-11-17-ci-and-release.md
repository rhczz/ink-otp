# GitHub 质量门禁与 tag 发布

日期：2026-11-17。状态：已推送默认分支，tag 自动发布与下载复核通过。

## 这次需要解决的问题

用户要求本轮阶段成果满足可用性、测试和质量要求后推送 GitHub；CI 必须真正阻止不合格代码，推送版本 tag 后应发布对应版本。必须存在 `.github/ci.yaml` 和 `.github/release.yaml`。

GitHub 只从 `.github/workflows/` 发现工作流。因此，用户指定的两个文件作为被实际读取的质量、发布配置；标准工作流放在 `.github/workflows/ci.yaml` 和 `.github/workflows/release.yaml`。不放置不会触发的假工作流，也不复制两套容易漂移的定义。

## 约定的行为

- 普通推送、PR 与发布复用同一套 CI。测试失败、跳过、覆盖率下降到门槛以下、独立格式对拍失败、权限或卸载规则回退、产物不合格都会使检查失败。
- 覆盖率包含全部 App 代码，不排除难测的系统适配或 UI 来美化数字；core 至少 90% 行 / 70% 分支，App 至少 75% 行 / 45% 分支。记录实际数字和未实测的硬件边界。
- 性能正确性由确定性测试把关：单前台 ticker、周期内复用、后台停止、锁定清缓存、解析预算。宿主 p95 和分配量保存为趋势，不用不稳定的毫秒数作 CI 红绿条件。
- tag 必须与源码版本严格一致。发布安装包必须使用固定签名，验证指纹、非调试、权限、对齐、版本与体积，再生成哈希和构建说明。
- CI 不读取发布私钥。发布 job 才获取加密 Secrets，使用临时文件并清理，校验失败不发布。没有签名材料时不生成新的临时身份冒充可升级版。
- 不强制推送，不覆盖已存在 Release，不把私钥、口令、真实账号数据或构建目录提交进仓库。

## 远端与权限核对

GitHub 连接器确认目标为用户的公开仓库 `rhczz/ink-otp`。SSH 可读取远端；CLI 的 API token 已失效，不能把该 token 当成有效的发布管理凭据。推送前先读取远端分支与文件，保留已有内容。

用户已确认保留当前 GPL-3.0，远端初始化 Apache-2.0 不作为本项目许可证。CLI 登录已由用户重新完成；用户明确授权把当前签名身份保存到 Actions 加密 Secrets。四项签名 Secrets 已通过 stdin 配置并仅核对名称，不在源码、制品或日志中保存值；上传前核验 keystore 的公开证书指纹与既有 APK 一致。尚未运行的 GitHub 工作流不能写成已经通过。

## 实际结果

本地 Python 全部 42 项测试通过，其中质量门禁 12 项、发布工具 23 项、存储配置 7 项。缺失报告、测试失败/跳过、覆盖率不足、APK 超限、ELF 对齐退化、缺失签名材料、错误证书、版本/历史/哈希异常都会受控失败。两份工作流通过 actionlint 1.7.12；只精确忽略它尚未识别、已核对官方文档支持的 concurrency.queue 字段。

完整 App 流程新增口令弹窗与明文迁移确认/取消测试；定位并验证了 Robolectric #8460 的非默认 411dp 测试屏宽与 Dialog 内输入框约束循环，功能回归改用 320dp 后两项通过，生产安全旗标、滚动和字段未因测试改动。完整本地 CI 已通过：core 105 / App 129 项，零失败、错误和跳过；core 行/分支 94.503% / 70.588%，App 79.647% / 53.771%。Debug/Release 构建与 Lint 无 Error/Fatal，未签名通用 APK 3106466 字节，4 ABI / 16 native ELF、16 KiB ZIP 与 LOAD 段、版本与权限均通过。真实 quality-gate.json 的 passed 为 true。远端 CI 和 tag 发布结果在实际执行后补充。

真实本地发布流水线也已通过：读取同一份 CI unsigned APK，按固定证书执行 zipalign → apksigner → 验签 → 哈希/身份/版本检查，生成 `dist/InkOTP-0.4.0-beta.1.apk`，并确认临时私钥文件被清理。此处是本机流水线验证，不是远端 Release 成功证明。

## 远端 CI 证据

[工作分支 CI 44061979850](https://github.com/rhczz/ink-otp/actions/runs/44061979850) 在提交 `c471105` 全部通过；下载实际门禁报告核对 passed=true、core 105 / App 129 项、行覆盖率 94.503% / 79.647%。GitHub 上 App 分支覆盖率为 53.802%，本机为 53.771%，均保留原始报告，不用不同环境的分母拼接数字。

同一提交已正常快进到默认分支 master，远端初始化提交保留；版本 tag `v0.4.0-beta.1` 已触发 [Release 44063347373](https://github.com/rhczz/ink-otp/actions/runs/44063347373)。该运行的 verify / sign / publish 三阶段全部成功，实际生成公开预发布版本；不是仅验证工作流语法。

## 已发布的结果

- 默认分支 `master` 的 [CI 44063347696](https://github.com/rhczz/ink-otp/actions/runs/44063347696) 通过，发布源提交为 `c47110511d125df14f480d2e3a281835d6468225`。
- [v0.4.0-beta.1 Release](https://github.com/rhczz/ink-otp/releases/tag/v0.4.0-beta.1) 于 2026-11-17 发布为 prerelease，含 APK、SHA256SUMS、build-info 和版本说明四项资产。
- 下载公开资产后再次调用正式 verify-bundle，版本、源码提交/运行编号、权限、ABI/ELF、ZIP 对齐、固定证书与所有哈希均通过。APK 为 3113246 字节，SHA-256 为 `55b224dab6d58611bc15d6e076f62570d37825f818f24d4dca2607a59b232d26`。
- 本地 `dist/InkOTP-0.4.0-beta.1.apk` 已同步为这份实际 GitHub 发布文件。未将任何 APK、私钥或本地签名配置提交到源码仓库。

源码与发布已完成；手机系统认证、相机、覆盖升级和真实手机性能仍按测试说明收集用户反馈，不记作已实测。GitHub 对旧 Node 20 Action 的兼容运行与 ubuntu-latest 迁移提示仍如实保留，没有将这些提示误记为测试失败或零警告。

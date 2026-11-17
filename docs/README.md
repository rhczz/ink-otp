# InkOTP 文档

标准流程与生态兼容原则见 [标准机制与互通](standards-and-interoperability.md)。M4 本轮范围见 [实施与验证记录](changes/2026-11-17-m4-standards-auth-performance.md)。

当前安装与手机验证见 [M4 手机说明](testing/m4-phone-test.md)，备份支持版本及独立验证依据见 [交换规格](../spec/interoperability.md)。

0.4 手机反馈修复见 [系统指纹入口、闪屏与到期颜色](changes/2026-11-17-phone-feedback-fixes.md)，交互回归范围见 [Android 交互契约](../spec/android-interaction.md)。

每次修改、功能开发及问题排查都记录在 [开发与问题记录](changes/README.md)。当前 M3 交付方式和实现计划见 [M3 内测版记录](changes/2026-11-17-m3-local-beta.md)。

| 文档 | 内容 |
|---|---|
| [design/2FA验证器-技术设计文档.md](design/2FA验证器-技术设计文档.md) | **唯一事实来源**：范围、架构、密钥体系、备份/库格式、UI 规范、测试、里程碑、工程约束 |
| [toolchain.md](toolchain.md) | 实际构建工具链、版本锁定、升级流程 |
| [threat-model.md](threat-model.md) | 资产、信任边界、威胁与缓解、明确不防御的场景 |
| [platform-neutrality.md](platform-neutrality.md) | 跨端数据契约规则 + iOS Phase 2 检查清单 |
| [exceptions.md](exceptions.md) | 例外/临时方案登记（未登记视为违规） |
| [releasing.md](releasing.md) | tag 自动发布、固定签名与失败处理 |
| [testing/github-ci.md](testing/github-ci.md) | 真实质量门禁与本地复现 |
| [release-checklist.md](release-checklist.md) | 发布前检查表（构建/安全/设计/演练） |

## 文档规则（与设计文档 §18.3 一致）

1. 先改文档，再改代码；格式/token/交互契约变更必须版本化。
2. 例外必须登记到 `exceptions.md`，并带 `TODO(C-xx)`、关联任务、清理版本。
3. `spec/` 是唯一的数据格式与测试向量来源；实现只能按 spec 写，不能反向定义 spec。
4. 本目录中的设计文档为实施口径；Obsidian vault 里的副本是快照，不自动同步。

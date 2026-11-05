# InkOTP 文档

| 文档 | 内容 |
|---|---|
| [design/2FA验证器-技术设计文档.md](design/2FA验证器-技术设计文档.md) | **唯一事实来源**：范围、架构、密钥体系、备份/库格式、UI 规范、测试、里程碑、工程约束 |
| [toolchain.md](toolchain.md) | 实际构建工具链、版本锁定、升级流程 |
| [threat-model.md](threat-model.md) | 资产、信任边界、威胁与缓解、明确不防御的场景 |
| [platform-neutrality.md](platform-neutrality.md) | 跨端数据契约规则 + iOS Phase 2 检查清单 |
| [exceptions.md](exceptions.md) | 例外/临时方案登记（未登记视为违规） |
| [release-checklist.md](release-checklist.md) | 发布前检查表（构建/安全/设计/演练） |

## 文档规则（与设计文档 §18.3 一致）

1. 先改文档，再改代码；格式/token/交互契约变更必须版本化。
2. 例外必须登记到 `exceptions.md`，并带 `TODO(C-xx)`、关联任务、清理版本。
3. `spec/` 是唯一的数据格式与测试向量来源；实现只能按 spec 写，不能反向定义 spec。
4. 本目录中的设计文档为实施口径；Obsidian vault 里的副本是快照，不自动同步。

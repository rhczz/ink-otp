# 修复 GitHub CI 的 Android SDK 初始化

日期：2026-11-17。状态：配置已修复，远端待验证。关联原记录：[GitHub 质量门禁与 tag 发布](2026-11-17-ci-and-release.md)。

## 为什么需要修复

首轮真实远端 [CI 44060979159](https://github.com/rhczz/ink-otp/actions/runs/44060979159) 在 Android SDK 初始化阶段失败，尚未进入项目测试。固定的 `setup-android v3.2.2` 默认安装 `tools platform-tools`，日志报告 `Failed to find package 'tools'`，进程以退出码 1 结束。这里的 `tools` 是已过时的包名；初始化失败不能当作 App 测试失败，也不能据本地通过宣称远端已通过。

## 这次的范围与验收方式

只修改 CI、发布签名、发布复核三个 `setup-android` 调用，显式将初始化包列表设为 `platform-tools`，并统一关闭许可证正文日志。保留原 Action SHA、命令行工具版本及后续 SDK 安装指令，不靠猜测升级版本解决错误。

验收先核对三个调用和 `actionlint` 静态检查，再由新的远端 run 确认初始化、Android 37.2 平台安装及完整门禁。没有执行的远端路径写为未验证。

## 修正后的行为

原来三个调用都继承了 Action 的默认包列表，因而都可能请求过时的 `tools`。现在三个调用都显式传入 `packages: platform-tools` 和 `log-accepted-android-sdk-licenses: false`，后续按原指令安装项目所需的平台与 build tools。

首轮日志还显示固定 Action 将运行器预装的命令行工具 20 替换为 16，随后在安装默认包时出错。已确认的直接失败原因是包名 `tools` 无法找到；目前没有证据表明命令行工具 16 无法安装 `platforms;android-37.2`，因此本轮不更换工具版本。

## 实际验证与限制

- 核对通过：两个 workflow 中共三个 `setup-android` 调用都只初始化 `platform-tools`，且均关闭许可证正文日志；Action SHA 和后续 SDK 安装指令未变。
- `actionlint 1.7.12` 通过；沿用原发布配置中已确认的精确忽略，只排除该版本尚不识别的 `concurrency.queue` 字段诊断。其余诊断没有忽略。
- `git diff --check` 通过。
- 远端修复结果未验证，Android 37.2 平台下载与完整项目测试尚需新 run 确认；不能将静态检查当成远端运行成功。本轮没有运行 Gradle、提交或推送，由主任务完成推送并跟进远端。

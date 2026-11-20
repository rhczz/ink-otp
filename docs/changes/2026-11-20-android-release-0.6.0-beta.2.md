# 发布 Android 0.6.0-beta.2

## 为什么发

0.6.0-beta.1 发布后，Google Authenticator 迁移已并入常用的添加、导入和导出流程，验证码卡片的依次落位也更明显。将这些改动作为 Android 预发布交付。

## 范围和验收

Android 版本名为 `0.6.0-beta.2`，构建号由 12 升至 13。继续使用既有包名与发布签名。将版本提交合入 `master` 后推送 `v0.6.0-beta.2` 标签，由 GitHub Actions 构建、签名并发布。既有提交、标签和 Release 保持可查。

发布后从 GitHub 重新下载 APK、校验和、构建信息和发布说明，核对哈希、版本、签名证书及对应的 Actions 运行。手机覆盖升级、Google Authenticator 互扫、相机及动效观感按实际设备验收。

本机新检出命中 Gradle 缓存时，核心测试的交换样本未恢复，导致独立校验缺少 `expected.json`。将样本目录纳入测试输出，再复验清空生成目录后的缓存恢复和完整检查。

## 执行结果

- 本机 `make ci` 通过。移走生成目录后，`:core:test` 命中缓存并恢复全部样本。独立 Python 对拍重新读取五种导出文件，通过。
- `v0.6.0-beta.2` 指向 `4bf4b06a770c1af0efb6661de76a6c0cc492d8cd`。GitHub 发布预检、Android CI、签名和发布均通过。标签运行核心 121 项，行覆盖 95.80%，分支 71.89%。App 200 项，行覆盖 82.23%，分支 59.53%。 [发布运行](https://github.com/rhczz/ink-otp/actions/runs/44693183430)
- GitHub 已公开预发布。重新下载全部四项资产，`SHA256SUMS` 的三条校验均通过。`build-info.json` 对应发布提交与运行。APK 核对包名 `io.github.rhczz.inkotp`、版本 `0.6.0-beta.2`、构建号 13、16 KiB 对齐和权限。签名证书与下载的上一版 APK 一致。 [GitHub Release](https://github.com/rhczz/ink-otp/releases/tag/v0.6.0-beta.2)
- 旧标签、14 个历史 Release 和 55 项已发布资产逐项复核一致。手机覆盖安装、Google Authenticator 真机互扫与动效观感本次未验证。

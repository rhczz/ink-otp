# 发布 Android 0.6.0-beta.2

## 为什么发

0.6.0-beta.1 发布后，Google Authenticator 迁移已并入常用的添加、导入和导出流程，验证码卡片的依次落位也更明显。将这些改动作为 Android 预发布交付。

## 范围和验收

Android 版本名为 `0.6.0-beta.2`，构建号由 12 升至 13。继续使用既有包名与发布签名。将版本提交合入 `master` 后推送 `v0.6.0-beta.2` 标签，由 GitHub Actions 构建、签名并发布。既有提交、标签和 Release 保持可查。

发布后从 GitHub 重新下载 APK、校验和、构建信息和发布说明，核对哈希、版本、签名证书及对应的 Actions 运行。手机覆盖升级、Google Authenticator 互扫、相机及动效观感按实际设备验收。

## 执行结果

待本次标签运行和远端资产复核。

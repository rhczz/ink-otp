# 在 GitHub 仓库添加官网入口

## 为什么改

仓库的 About 没有官网地址，首页 README 也没有官网入口。读者需要从部署记录中找地址。

## 范围和验收

在仓库 About 的 Website 字段和 README 顶部加入 InkOTP 官网。验收时核对公开仓库展示的链接，以及官网 HTTPS 返回的页面标题和 canonical 地址。

## 验证结果

官网返回 HTTPS 200，页面标题为「InkOTP · 离线两步验证器」，canonical 与 README 的链接一致。GitHub 仓库 API 已回读并确认 About 的 Website 值为同一地址。 [InkOTP 官网](https://inkotp.renhongchang.top/)

# InkOTP 官网与隐私声明

## 为什么做

华为应用市场需要填写能公开访问的隐私声明网址。InkOTP 同时需要一个官方首页，让用户找到 Android 下载、源码和公测说明。部署域名为 `inkotp.renhongchang.top`，用户将在本机取得静态目录后自行部署。

## 范围和验收方式

- `website/` 放置首页和 `privacy/` 隐私页。网站使用静态 HTML、CSS 和本地图片，不需要构建或在线服务。现有标志和模拟账号截图保持 Quiet Ink 品牌。
- 按 0.5.0-beta.1 签名 APK 和相应源码说明权限、处理目的、设备存储、导出、第三方组件和联系方式。对官网托管及 Android 应用行为分别表述。
- 验证两页在桌面和手机视口可读，首页可进入隐私页，隐私页可直接访问并能返回。核对链接、图片、键盘焦点与页面网络请求。
- 提交源码并准备与提交内容一致的本地发布目录。域名、HTTPS 和对外发布由用户完成。

## 实际结果

- `website/index.html` 提供 Android 公测包、版本说明和源码入口。`website/privacy/index.html` 说明相机与系统认证权限、本机处理和备份、开源组件、官网访问及开发者联系渠道。隐私页可在 `/privacy/` 直接打开。
- 复用 0.5 公测图标和使用虚构账号生成的应用界面截图。HTML、CSS 和两张图片均存放在发布目录。两个页面不发起第三方资源请求。
- 在 Chrome 用 1440、768、700、390 和 320 像素宽度验证两页。页面直接返回 HTTP 200，无横向溢出。检查了图片、键盘跳过导航、目录锚点、首页与隐私页往返、邮箱和 APK 链接、页面请求及控制台。另复查了桌面、平板和手机截图。
- 仓库文件门禁和补丁空白检查通过。本机未重跑 Android 测试，此次交付只涉及静态网站和变更记录。

## 部署

1. 在 Cloudflare 的 Workers & Pages 创建 Pages 项目，选择直接上传。上传 `website/` 或本机交付的 `dist/inkotp-website/` 目录。上传目录的顶层应看到 `index.html`、`privacy/` 和 `assets/`。 [Cloudflare 直接上传文档](https://developers.cloudflare.com/pages/get-started/direct-upload/)
2. 在该项目的自定义域名设置添加 `inkotp.renhongchang.top`。若域名 DNS 由其他服务商管理，添加页面给出的 CNAME，指向项目的 `pages.dev` 域名。 [Cloudflare 自定义域名文档](https://developers.cloudflare.com/pages/configuration/custom-domains/)
3. HTTPS 生效后，用未登录的浏览器直接访问 [InkOTP 隐私声明](https://inkotp.renhongchang.top/privacy/)。确认可以直接阅读中文全文，再把该网址填入华为开发者中心。正式域名、HTTPS 和境内访问均待部署后验证。

当前 0.5 公测包的应用内入口只有简短的隐私说明。华为要求网页与应用内的隐私声明内容一致，提交审核前需在 Android 应用中提供同一份完整声明。 [华为隐私声明链接常见问题](https://developer.huawei.com/consumer/cn/doc/App/50128/)

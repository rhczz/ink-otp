# 架构、数据存储、密钥保护与卸载清理说明

日期：2026-11-17。对应版本：0.3.0-beta.1。

## 为什么补充这份说明

用户已安装 M3 内测 APK 并试用，反馈“非常好用，暂时没有发现什么问题”，随后询问架构、Android 兼容范围、写入目录、密钥保护和卸载残留。本次对照实际源码、交付 APK 与 Android 官方资料核查，仅补充文档，没有修改应用代码或重新生成安装包。

这次反馈能确认安装和初步使用正常；手机型号、Android 版本以及生物识别、备份恢复等逐项测试结果尚未提供，因此不能把整份 M3 真机验收清单标为通过。

## 软件如何组织

这是原生 Kotlin / Jetpack Compose Android 应用，采用 MVVM 风格的分层：

- 界面负责展示、输入和导航。
- `VaultViewModel` 与 `VaultActions` 负责状态、账号操作、出码、锁定及异步操作协调。
- `VaultRepository` 负责库文件、加密会话、口令与设备密钥包装。
- `:core` 是不依赖 Android 的纯 Kotlin 内核，负责 Base32、OTP、URI、加密及库/备份格式。
- Android 适配负责相机、图片读取、生物识别、Keystore、剪贴板和系统文件选择器。

Gradle 模块只有 `:app` 与 `:core`。`docs/`、`spec/` 和 Python 工具服务于开发与验证；App 没有后端服务，也没有云端账户系统，不申请联网权限。账号数据保存在本机加密文件中。

## Android 兼容范围

已交付 APK 的最低版本是 Android 8.0 / API 26，targetSdk 为 36。APK 包含 arm64-v8a、armeabi-v7a、x86 和 x86_64 四种架构的原生依赖，覆盖常见 32/64 位 ARM 和 x86 设备；并非每个品牌都要单独编译。

这不等于所有 Android 8.0 以上设备和未来系统都已验证。厂商相机、生物识别、Keystore、文件提供器及系统策略可能不同。没有相机或不授予权限时可手动添加；没有可用的强生物识别时可用口令。基本使用不依赖 Google 账号或 Play 服务。

本次只是确认包内架构和 SDK 声明，没有在所有机型上安装。用户本人的初步试用增加了一条正向反馈，具体机型待补充。

## 手机上写到哪里

以下是应用业务代码确定写入的内容。以普通主用户为例，私有数据根目录通常是：

```text
/data/user/0/io.github.rhczz.inkotp/
```

有些系统也显示为 `/data/data/io.github.rhczz.inkotp/`。用户编号、分身、工作资料或存储配置可能改变实际路径；代码使用 Android 的 `filesDir` 等 API 获取位置，并未硬编码这个示例路径。这里没有通过 adb 检查用户手机上的实际目录。

| 位置，相对于私有根目录 | 写入内容 |
|---|---|
| `files/vault.bin` | AES-256-GCM 加密后的完整账号库：种子、服务名、账号名、算法、位数、周期、排序和库设置。文件头含版本、随机参数、盐以及存在口令时的加密 DEK 包装，不含明文种子或明文口令。 |
| `files/vault.bin.new` / `.bak` 等 | 原子写入过程可能出现的同目录临时或恢复文件，具体随系统 AtomicFile 实现而异。写入的是同一类加密库字节。 |
| `files/datastore/device_wrap.preferences_pb` | 解锁模式、IV、盐及加密后的设备包装信封。生物识别模式包装 DEK+KEK；跳过口令模式只包装 DEK。DataStore 本身不是额外加密层，秘密材料在写入前已经加密；Base64 只是存储编码。事务过程中可能出现 `.tmp` 等文件。 |
| `shared_prefs/vault_metadata.xml` | 最近备份时间 `lastBackupAt` 与备份对应的库更新时间 `backupRevision`。不含账号列表、种子或恢复口令。 |
| 系统或 AndroidX 管理的私有缓存、代码缓存等 | 运行时可能生成字体、代码或组件缓存，不能只从业务源码承诺目录中永远只有上述文件。业务代码没有另行写入明文账号库。 |

Android 的应用私有存储由系统沙箱隔离，普通其他应用不能直接读取。[官方私有存储说明](https://developer.android.com/training/data-storage/app-specific)

以下内容属于私有目录之外：

- **手动导出的 `.2fab`**：通过系统文件选择器写入用户选定的目录、SD 卡或文件提供器；没有固定的公共目录，也不会自动在下载或图片目录散落账号文件。建议文件名为 `inkotp-backup-日期.2fab`。选择网盘提供器时，文件由该提供器管理；这不代表 InkOTP 自身在后台同步。
- **剪贴板中的验证码**：用户复制时暂存于系统剪贴板，默认请求 30 秒清理并设置敏感标记。实际清理受系统后台权限影响；其它应用的历史记录不由 InkOTP 管理。
- **原相册二维码和下载的 APK**：这些是用户已有或另行保存的文件。扫码与图片识别不保存照片；相机只做预览和帧分析，相册图片只读取和在内存解码。

## 密钥如何保存和保护

“密钥”有不同用途，位置也不同：

| 名称 | 用途与位置 |
|---|---|
| 服务的 OTP 种子 | 用来生成验证码。静态保存于加密库；解锁后在内存参与运算，未作为明文单独落盘。 |
| 数据密钥 DEK | 每个新库随机生成 32 字节，用来加密库。恢复口令模式在库头保存其加密包装；设备绑定或生物识别模式另有系统密钥保护的信封。 |
| 口令派生密钥 KEK | 由恢复口令经 Argon2id 派生，默认 64 MiB、3 次、并行度 2，用来保护 DEK 和加密备份。解锁期驻内存；生物识别信封中保存它的加密副本。 |
| Android Keystore 包装密钥 | Android 系统按应用隔离管理，用来加解密设备信封，不是 App 目录里可以直接打开的密钥文件。别名为 `io.github.rhczz.inkotp.session.v1.biometric` 或 `.device-bound`。 |

恢复口令不会作为明文写进应用数据。生物识别密钥每次使用需要强生物识别授权；指纹等录入改变后可以使用恢复口令回退。跳过口令的设备绑定模式没有这种应用内强认证保障，并且换机/重装无法恢复，这是界面明确提示的降级模式。

Keystore 密钥通过系统接口不可直接导出。代码优先尝试 StrongBox，失败时回退普通 Keystore；是否真正处于硬件安全区、处于哪一级，取决于手机能力，尚未在用户这台手机上核实。不能把“用了 Keystore”写成“每台手机都有同样的安全芯片”。[Android Keystore 官方说明](https://developer.android.com/privacy-and-security/keystore)

锁定会主动擦除主要可变密钥数组和种子数组，并释放会话引用。界面输入及口令规范化会产生 Kotlin/Java 不可变 String，垃圾回收和内存复制不完全由应用控制，因此不承诺内存中每个副本都可被证明擦除。加密、沙箱和系统认证提供实际防护，但不能承诺已被控制的系统或已解锁进程绝对安全。

APK 的签名密钥与上述数据密钥用途不同：签名私钥留在开发电脑，用于后续版本的身份与覆盖升级；不随 APK 分发，也不参与解密用户账号库。

## 卸载时会清理哪些内容

在标准 Android 的普通完整卸载流程下：

- 应用私有库、DataStore、SharedPreferences、私有临时文件和缓存由系统删除。[官方存储规则](https://developer.android.com/training/data-storage/app-specific)
- 应用归属的 Keystore 密钥由系统清理。AOSP 的 `clearNamespace` 接口明确用于应用移除时清理该命名空间中的密钥。[AOSP 清理接口](https://android.googlesource.com/platform/system/security/+/refs/heads/main/keystore2/aidl/android/security/maintenance/IKeystoreMaintenance.aidl)
- 用户导出的 `.2fab`、原二维码图片、下载的 APK、复制到其它位置或网盘的文件，会继续由其所有者管理，不能依靠卸载 InkOTP 删除。系统文件选择器产生的共享文档本就设计为卸载后保留。[SAF 官方说明](https://developer.android.com/training/data-storage/shared/documents-files)

应用已设置 `allowBackup=false`，并通过备份与数据提取规则排除私有文件的云备份和设备迁移。这是当前实现的标准 Android 配置，不等于能控制厂商额外备份工具或用户自行复制的文件。

“归档”或选择“保留应用数据”会保留数据。卸载也不意味着闪存经过逐块物理覆写，当前应用没有提供取证级安全擦除功能；更不能证明第三方剪贴板、文件历史或网盘副本没有残留。对于本应用，可信的表述是：**普通卸载清除本机私有数据和系统密钥；外部文件需要用户单独管理，无法承诺全手机零残留。**

本轮未执行卸载实验，也未查看用户手机存储。以上把当前代码事实、Android 标准规则和仍需设备验证的边界分开说明，避免把源码判断写成真机实测。

## 主要源码依据

- [存储路径与包装字段](../../android/app/src/main/kotlin/io/github/rhczz/inkotp/data/VaultStorage.kt)
- [库会话和密钥清理](../../android/app/src/main/kotlin/io/github/rhczz/inkotp/data/VaultRepository.kt)
- [Keystore 参数](../../android/app/src/main/kotlin/io/github/rhczz/inkotp/data/DeviceKeys.kt)
- [口令派生](../../android/core/src/main/kotlin/io/github/rhczz/inkotp/core/crypto/Kdf.kt)
- [系统文件读写](../../android/app/src/main/kotlin/io/github/rhczz/inkotp/platform/BackupFiles.kt)
- [备份与迁移配置](../../android/app/src/main/AndroidManifest.xml)

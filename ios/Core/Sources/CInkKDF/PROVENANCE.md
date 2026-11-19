# iOS 备份密钥派生依赖

Apple 的公开 SDK 提供 AES-GCM 和 PBKDF2。现有 InkOTP 与 Aegis 文件分别要求 Argon2id v1.3 和 scrypt。为保留备份互通，使用固定版本的官方参考实现。编译和运行均无需获取远程依赖。

- Argon2id 使用 PHC 参考实现 20190702，提交 `62358ba2123abd17fccf2a108a301d4b52c01a7c`。C 源文件保持原样，保留 Apache-2.0 或 CC0 的许可证文本。 [官方发布](https://github.com/P-H-C/phc-winner-argon2/releases/tag/20190702)
- scrypt 使用 Tarsnap C 参考实现，提交 `cbdef576b8d4640e86b208fa3f063d4ec449110a`。保留源文件的版权和 BSD 许可。唯一适配是通过 CommonCrypto 执行 PBKDF2 并检查返回值，scrypt 混合步骤沿用上游。 [固定上游提交](https://github.com/Tarsnap/scrypt/tree/cbdef576b8d4640e86b208fa3f063d4ec449110a)

Swift 在调用密钥派生前检查口令和文件内容长度、版本和 KDF 内存及工作量预算。互通测试取用独立生成的 .2fab 夹具和公开 Aegis 样本。

#!/usr/bin/env python3
"""Create a persistent, ignored local signer and build an installable beta APK."""
from pathlib import Path
import os
import secrets
import subprocess

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android"
PRIVATE = ANDROID / ".local-signing"
CONFIG = ANDROID / "local-signing.properties"
KEYSTORE = PRIVATE / "inkotp-local-beta.jks"


def main():
    os.umask(0o077)
    PRIVATE.mkdir(mode=0o700, exist_ok=True)
    if not KEYSTORE.exists():
        if CONFIG.exists():
            raise SystemExit("签名配置已存在但私钥丢失；请恢复原私钥，避免破坏覆盖升级。")
        password = secrets.token_urlsafe(36)
        passfile = PRIVATE / "store-password"
        passfile.write_text(password, encoding="utf-8")
        try:
            subprocess.run([
                "keytool", "-genkeypair", "-keystore", str(KEYSTORE),
                "-storetype", "JKS", "-alias", "inkotp-local-beta",
                "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
                "-dname", "CN=InkOTP Local Beta,O=InkOTP,C=CN",
                "-storepass:file", str(passfile), "-keypass:file", str(passfile),
            ], check=True)
            CONFIG.write_text(
                "storeFile=.local-signing/inkotp-local-beta.jks\n"
                f"storePassword={password}\nkeyAlias=inkotp-local-beta\nkeyPassword={password}\n",
                encoding="utf-8",
            )
        finally:
            passfile.unlink(missing_ok=True)
    if not CONFIG.exists():
        raise SystemExit("签名私钥已存在但配置丢失；请恢复原配置。")
    subprocess.run([str(ANDROID / "gradlew"), ":app:assembleRelease", "--console=plain"], cwd=ANDROID, check=True)
    print("本地签名 APK：android/app/build/outputs/apk/release/app-release.apk")


if __name__ == "__main__":
    main()

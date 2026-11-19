#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "Usage: verify-device.sh unsigned.ipa [InkOTP.xcarchive]" >&2
  exit 2
fi

python3 - "$@" <<'PY'
import hashlib
import json
import plistlib
import stat
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

BUNDLE = "io.github.rhczz.inkotp"
APP = "InkOTP.app"
MINIMUM = "17.0"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify(ipa, archive):
    with zipfile.ZipFile(ipa) as package:
        names = package.namelist()
        require(len(names) == len(set(names)), "Duplicate IPA entries")
        for entry in package.infolist():
            name = entry.filename.rstrip("/")
            parts = name.split("/")
            require(name == "Payload" or parts[:2] == ["Payload", APP], "Unexpected IPA payload")
            require(not {"", ".", "..", "_CodeSignature", "embedded.mobileprovision"}.intersection(parts)
                    and "\\" not in name and not stat.S_ISLNK(entry.external_attr >> 16),
                    "Unexpected IPA path, symlink, signature, or provisioning profile")

    with tempfile.TemporaryDirectory(prefix="inkotp-ipa-verify-") as extracted:
        subprocess.run(["ditto", "-x", "-k", str(ipa), extracted], check=True)
        app = Path(extracted) / "Payload" / APP
        binary = app / "InkOTP"
        arch = subprocess.check_output(["xcrun", "lipo", "-archs", str(binary)], text=True).split()
        require(arch == ["arm64"], f"Expected an ARM64-only app, got {arch}")
        build = subprocess.check_output(["xcrun", "vtool", "-show-build", str(binary)], text=True)
        fields = [(line.split()[0], line.split()[1]) for line in build.splitlines() if len(line.split()) == 2]
        require([v.upper() for k, v in fields if k == "platform"] in (["IOS"], ["2"]), "Expected an iPhoneOS Mach-O")
        require([v for k, v in fields if k == "minos"] in ([MINIMUM], [MINIMUM + ".0"]), "Unexpected Mach-O deployment target")
        info = plistlib.loads((app / "Info.plist").read_bytes())
        require(info.get("CFBundleIdentifier") == BUNDLE and info.get("CFBundleExecutable") == "InkOTP"
                and info.get("CFBundlePackageType") == "APPL", "Unexpected iOS app bundle or executable")
        require(info.get("CFBundleSupportedPlatforms") == ["iPhoneOS"] and info.get("DTPlatformName") == "iphoneos"
                and info.get("MinimumOSVersion") == MINIMUM, "Unexpected iPhoneOS bundle metadata")
        require(info.get("UIDeviceFamily") == [1, 2], "Expected iPhone and iPad support")
        privacy = plistlib.loads((app / "PrivacyInfo.xcprivacy").read_bytes())
        require(privacy.get("NSPrivacyTracking") is False and privacy.get("NSPrivacyTrackingDomains") == []
                and privacy.get("NSPrivacyCollectedDataTypes") == [] and privacy.get("NSPrivacyAccessedAPITypes"),
                "Unexpected privacy manifest declarations")

        if archive:
            props = plistlib.loads((archive / "Info.plist").read_bytes())["ApplicationProperties"]
            require(props.get("ApplicationPath") == f"Applications/{APP}" and props.get("Architectures") == arch
                    and props.get("CFBundleIdentifier") == BUNDLE, "Unexpected native Xcode archive metadata")
            archived = archive / "Products" / "Applications" / APP
            for name in ("InkOTP", "Info.plist", "PrivacyInfo.xcprivacy"):
                require(subprocess.run(["cmp", "-s", str(archived / name), str(app / name)]).returncode == 0,
                        f"The IPA has a different {name} from the Xcode archive")

    return {"file": ipa.name, "sha256": hashlib.sha256(ipa.read_bytes()).hexdigest(), "architecture": arch[0],
            "platform": "iphoneos", "minimumOSVersion": MINIMUM, "bundleIdentifier": BUNDLE,
            "version": info["CFBundleShortVersionString"], "build": info["CFBundleVersion"],
            "deviceFamilies": ["iPhone", "iPad"], "privacyManifest": True,
            "provisionedForInstallation": False, "archiveVerified": archive is not None}


try:
    archive = Path(sys.argv[2]) if len(sys.argv) == 3 else None
    print(json.dumps(verify(Path(sys.argv[1]), archive), indent=2))
except (KeyError, OSError, ValueError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
    print(f"iPhoneOS IPA verification failed: {error}", file=sys.stderr)
    raise SystemExit(1) from None
PY

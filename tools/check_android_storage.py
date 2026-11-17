#!/usr/bin/env python3
"""Check the manifest and backup rules behind InkOTP's standard data lifecycle."""
import argparse
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
DOMAINS = {"root", "file", "database", "sharedpref", "device_root", "device_file",
           "device_database", "device_sharedpref", "external"}


def validate(project_root: Path, manifest_path: Path | None = None) -> list[str]:
    errors = []
    resource_root = project_root / "android/app/src/main/res/xml"
    manifest = manifest_path or project_root / "android/app/src/main/AndroidManifest.xml"
    try:
        root = ET.parse(manifest).getroot()
        app = root.find("application")
        if app is None:
            return ["Manifest 缺少 application"]
        for attribute in ["allowBackup", "hasFragileUserData"]:
            if app.get(ANDROID + attribute) != "false":
                errors.append(f"application 必须显式设置 {attribute}=false")
        if app.get(ANDROID + "requestLegacyExternalStorage") == "true":
            errors.append("不得开启公共存储旧版访问模式")
        if app.get(ANDROID + "allowClearUserData") == "false":
            errors.append("不得阻止用户使用系统清除数据流程")
        for attribute, sections in [("fullBackupContent", [None]),
                                    ("dataExtractionRules", ["cloud-backup", "device-transfer"])]:
            reference = app.get(ANDROID + attribute, "")
            if not re.fullmatch(r"@xml/[a-z0-9_]+", reference):
                errors.append(f"{attribute} 必须引用明确的 XML 排除规则")
                continue
            rules = ET.parse(resource_root / (reference.removeprefix("@xml/") + ".xml")).getroot()
            for section in sections:
                node = rules if section is None else rules.find(section)
                label = section or "full-backup-content"
                if node is None:
                    errors.append(f"缺少 {label} 排除规则")
                    continue
                exclusions = {e.get("domain") for e in node.findall("exclude") if e.get("path") == "."}
                missing = sorted(DOMAINS - exclusions)
                if missing:
                    errors.append(f"{label} 未完整排除私有数据：{', '.join(missing)}")
    except (OSError, ET.ParseError):
        errors.append("Manifest 或备份排除规则无法读取/解析")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--manifest", type=Path)
    args = parser.parse_args()
    errors = validate(args.root, args.manifest)
    for error in errors:
        print("FAIL:", error)
    if not errors:
        print("PASS: 私有数据备份/迁移排除与标准卸载配置完整；用户导出文件由用户管理")
    raise SystemExit(bool(errors))


if __name__ == "__main__":
    main()

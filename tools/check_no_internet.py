#!/usr/bin/env python3
"""Fail if an Android merged manifest grants network or external-storage access.

InkOTP is offline by contract: it must not declare INTERNET or
ACCESS_NETWORK_STATE.  As defense in depth this check also rejects broad
READ_*/WRITE_*/MANAGE_* permissions (external storage, media, contacts, etc.):
a 2FA app has no reason to request them.

Usage:
  python tools/check_no_internet.py --manifest <merged AndroidManifest.xml>

Exit codes: 0 = clean, 1 = forbidden permission present, 2 = usage/parse error.
"""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID_NS = "http://schemas.android.com/apk/res/android"
NETWORK_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
}
STORAGE_PREFIXES = (
    "android.permission.READ_",
    "android.permission.WRITE_",
    "android.permission.MANAGE_",
)


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _permission_name(element: ET.Element) -> str | None:
    # Prefer the Android namespace, but tolerate a missing namespace (some
    # hand-written test manifests omit it).
    value = element.get(f"{{{ANDROID_NS}}}name")
    if value is None:
        value = element.get("name")
    if value is None:
        for key, candidate in element.attrib.items():
            if _local_name(key) == "name":
                return candidate
    return value


def collect_permissions(root: ET.Element) -> list[str]:
    permissions: list[str] = []
    seen: set[str] = set()
    for element in root.iter():
        if not _local_name(element.tag).startswith("uses-permission"):
            continue
        name = _permission_name(element)
        if name and name not in seen:
            seen.add(name)
            permissions.append(name)
    return permissions


def classify(permission: str) -> str | None:
    if permission in NETWORK_PERMISSIONS:
        return "network access is forbidden (offline app)"
    if permission.startswith(STORAGE_PREFIXES):
        return "broad READ_/WRITE_/MANAGE_ permission is forbidden"
    return None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Reject network/storage permissions in a merged manifest")
    parser.add_argument("--manifest", required=True, help="path to merged AndroidManifest.xml")
    args = parser.parse_args(argv)

    path = Path(args.manifest)
    try:
        tree = ET.parse(path)
    except OSError as exc:
        print(f"error: cannot read manifest {path}: {exc}", file=sys.stderr)
        return 2
    except ET.ParseError as exc:
        print(f"FAIL: cannot parse manifest {path}: {exc}", file=sys.stderr)
        return 2

    permissions = collect_permissions(tree.getroot())
    print(f"Manifest: {path}")
    if permissions:
        print(f"Permissions ({len(permissions)}):")
        for permission in permissions:
            print(f"  - {permission}")
    else:
        print("Permissions (0): (none)")

    violations: list[tuple[str, str]] = []
    for permission in permissions:
        reason = classify(permission)
        if reason:
            violations.append((permission, reason))

    if violations:
        print("Forbidden permissions:")
        for permission, reason in violations:
            print(f"  - {permission}: {reason}")
        print(f"FAIL: {len(violations)} forbidden permission(s)")
        return 1

    print("PASS: no network or broad storage permissions")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

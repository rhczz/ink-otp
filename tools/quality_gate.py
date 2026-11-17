#!/usr/bin/env python3
"""Fail closed on missing or regressed CI evidence; uses only the Python stdlib."""
from __future__ import annotations

import argparse
import json
from pathlib import Path, PurePosixPath
import re
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ANDROID = "{http://schemas.android.com/apk/res/android}"
CAMERA_FEATURES = {"android.hardware.camera", "android.hardware.camera.any", "android.hardware.camera.autofocus"}


def read_xml(path: Path, tag: str):
    root = ET.parse(path).getroot()
    if root.tag != tag:
        raise ValueError(f"expected {tag}, got {root.tag}")
    return root


def inspect_junit(directory: Path, minimum: int) -> tuple[list[str], dict]:
    errors, total = [], 0
    reports = sorted(directory.glob("TEST-*.xml"))
    if not reports:
        errors.append(f"{directory}: missing JUnit reports")
    for path in reports:
        try:
            suite = read_xml(path, "testsuite")
            cases = suite.findall("testcase")
            count = int(suite.attrib["tests"])
            if count <= 0 or count != len(cases):
                errors.append(f"{path}: test count is zero or differs from testcase evidence")
            for attribute in ["failures", "errors", "skipped"]:
                if int(suite.attrib[attribute]) != 0:
                    errors.append(f"{path}: nonzero {attribute}")
            if any(node.tag in {"failure", "error", "skipped"} for node in suite.iter()):
                errors.append(f"{path}: failed, errored or skipped testcase")
            total += len(cases)
        except (OSError, ET.ParseError, KeyError, ValueError) as exc:
            errors.append(f"{path}: unreadable JUnit evidence: {exc}")
    if total < max(1, minimum):
        errors.append(f"{directory}: {total} tests, require at least {max(1, minimum)}")
    return errors, {"tests": total, "reports": len(reports)}


def inspect_coverage(path: Path, thresholds: dict) -> tuple[list[str], dict]:
    errors, info = [], {}
    try:
        root = read_xml(path, "report")
        for kind, minimum in thresholds.items():
            counters = [node for node in root.findall("counter") if node.get("type") == kind]
            if len(counters) != 1:
                raise ValueError(f"missing or ambiguous top-level {kind} counter")
            covered, missed = (int(counters[0].attrib[key]) for key in ["covered", "missed"])
            if covered < 0 or missed < 0 or covered + missed == 0:
                raise ValueError(f"empty or invalid {kind} counter")
            percentage = 100 * covered / (covered + missed)
            info[kind] = {"covered": covered, "missed": missed, "percent": round(percentage, 3)}
            if percentage < minimum:
                errors.append(f"{path}: {kind} {percentage:.3f}% < {minimum}%")
    except (OSError, ET.ParseError, KeyError, ValueError) as exc:
        errors.append(f"{path}: unreadable coverage evidence: {exc}")
    return errors, info


def inspect_lint(path: Path) -> tuple[list[str], dict]:
    errors, warnings = [], 0
    try:
        root = read_xml(path, "issues")
        for issue in root.findall("issue"):
            if issue.get("severity") in {"Error", "Fatal"}:
                errors.append(f"{path}: {issue.get('severity')} {issue.get('id')}")
            elif issue.get("severity") == "Warning":
                warnings += 1
    except (OSError, ET.ParseError, ValueError) as exc:
        errors.append(f"{path}: unreadable lint evidence: {exc}")
    return errors, {"warnings": warnings}


def inspect_manifest(path: Path, allowed_permissions: list[str]) -> tuple[list[str], dict]:
    errors, permissions = [], []
    try:
        root = read_xml(path, "manifest")
        app = root.find("application")
        if app is None or app.get(ANDROID + "debuggable", "false") != "false":
            errors.append(f"{path}: missing application or debuggable release application")
        for node in root:
            if node.tag.startswith("uses-permission"):
                name = node.get(ANDROID + "name")
                permissions.append(name)
                if name not in allowed_permissions:
                    errors.append(f"{path}: forbidden permission {name}")
        for feature in CAMERA_FEATURES:
            nodes = [node for node in root.findall("uses-feature") if node.get(ANDROID + "name") == feature]
            if not nodes or any(node.get(ANDROID + "required") != "false" for node in nodes):
                errors.append(f"{path}: {feature} must explicitly be optional")
    except (OSError, ET.ParseError, ValueError) as exc:
        errors.append(f"{path}: unreadable manifest: {exc}")
    return errors, {"permissions": sorted(permissions, key=str)}


def elf_load_alignments(data: bytes) -> list[int]:
    """Read 32/64-bit ELF PT_LOAD headers without trusting external executables."""
    if len(data) < 52 or data[:4] != b"\x7fELF" or data[4] not in {1, 2} or data[5] not in {1, 2}:
        raise ValueError("invalid ELF header")
    endian, wide = ("<" if data[5] == 1 else ">"), data[4] == 2
    offset = struct.unpack_from(endian + ("Q" if wide else "I"), data, 32 if wide else 28)[0]
    entry_size, count = struct.unpack_from(endian + "HH", data, 54 if wide else 42)
    expected = 56 if wide else 32
    if entry_size != expected or count == 0 or offset + entry_size * count > len(data):
        raise ValueError("invalid ELF program-header table")
    alignments = []
    for index in range(count):
        fields = struct.unpack_from(endian + ("IIQQQQQQ" if wide else "IIIIIIII"), data, offset + entry_size * index)
        if fields[0] != 1:  # PT_LOAD
            continue
        file_offset, virtual_address = (fields[2], fields[3]) if wide else (fields[1], fields[2])
        alignment = fields[-1]
        if alignment < 16384 or alignment & (alignment - 1) or (virtual_address - file_offset) % alignment:
            raise ValueError(f"PT_LOAD requires congruent power-of-two alignment >= 16384, got {alignment}")
        alignments.append(alignment)
    if not alignments:
        raise ValueError("ELF has no PT_LOAD segments")
    return alignments


def inspect_apk(path: Path, max_bytes: int, required_abis: list[str]) -> tuple[list[str], dict]:
    """Shared CI/release API. ELF alignment and ZIP page alignment are distinct checks."""
    path = Path(path)
    errors, info = [], {"native_libraries": {}, "abis": [], "size_bytes": 0}
    try:
        info["size_bytes"] = path.stat().st_size
        if info["size_bytes"] >= max_bytes:
            errors.append(f"{path}: APK must be smaller than {max_bytes} bytes")
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            if len(names) != len(set(names)):
                errors.append(f"{path}: duplicate ZIP entries")
            for required in ["classes.dex", "AndroidManifest.xml"]:
                if required not in names or archive.getinfo(required).file_size == 0:
                    errors.append(f"{path}: missing/empty {required}")
            libraries = {abi: set() for abi in required_abis}
            for name in names:
                if not name.endswith(".so"):
                    continue
                parts = PurePosixPath(name).parts
                if len(parts) != 3 or parts[0] != "lib" or parts[1] not in libraries:
                    errors.append(f"{path}: unexpected native library {name}")
                    continue
                libraries[parts[1]].add(parts[2])
                try:
                    # Bound decompression independently of the compressed APK size.
                    if archive.getinfo(name).file_size > 64 * 1024 * 1024:
                        raise ValueError("native library exceeds inspection budget")
                    info["native_libraries"][name] = elf_load_alignments(archive.read(name))
                except (ValueError, struct.error) as exc:
                    errors.append(f"{path}!{name}: {exc}")
            info["abis"] = sorted(abi for abi, libs in libraries.items() if libs)
            if set(info["abis"]) != set(required_abis):
                errors.append(f"{path}: expected ABIs {required_abis}, got {info['abis']}")
            if any(len(libs) != 4 for libs in libraries.values()) or len({tuple(sorted(libs)) for libs in libraries.values()}) != 1:
                errors.append(f"{path}: require the same four native libraries in each ABI (16 total)")
    except (OSError, zipfile.BadZipFile, RuntimeError, KeyError, ValueError) as exc:
        errors.append(f"{path}: unreadable APK: {exc}")
    return errors, info


def inspect_metadata(path: Path, apk: Path, package_id: str) -> tuple[list[str], dict]:
    try:
        metadata = json.loads(path.read_text())
        elements = metadata["elements"]
        if metadata["applicationId"] != package_id or len(elements) != 1:
            raise ValueError("unexpected package or ambiguous APK output")
        element = elements[0]
        if (element["outputFile"] != apk.name or type(element["versionCode"]) is not int
                or element["versionCode"] <= 0 or not isinstance(element["versionName"], str)
                or not element["versionName"]):
            raise ValueError("invalid APK filename or version metadata")
        return [], element
    except (OSError, ValueError, KeyError, TypeError) as exc:
        return [f"{path}: unreadable APK metadata: {exc}"], {}


def inspect_hygiene(root: Path) -> tuple[list[str], dict]:
    errors = []
    try:
        result = subprocess.run(["git", "-C", str(root), "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                                check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        paths = set(result.stdout.decode().rstrip("\0").split("\0")) - {""}
        for name in sorted(paths):
            relative = PurePosixPath(name)
            if (relative.suffix.lower() in {".apk", ".aab", ".jks", ".keystore", ".p12", ".pfx", ".pem", ".key"}
                    or "dist" in relative.parts or ".local-signing" in relative.parts
                    or relative.name in {"local-signing.properties", "local.properties"}):
                errors.append(f"tracked/unignored private or generated file: {name}")
            path = root / name
            if path.is_file() and path.stat().st_size < 2 * 1024 * 1024:
                if re.search(rb"(?m)^-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----\r?$", path.read_bytes()):
                    errors.append(f"private key material in {name}")
        return errors, {"files": len(paths)}
    except (OSError, UnicodeError, subprocess.CalledProcessError) as exc:
        return [f"cannot inspect repository hygiene: {exc}"], {}


def run(root: Path, config: dict, hygiene: bool = True) -> tuple[list[str], dict]:
    errors, evidence = [], {}
    checks = []
    for module, settings in config["modules"].items():
        checks += [(module + "_junit", inspect_junit, (root / settings["junit"], settings["minimum_tests"])),
                   (module + "_coverage", inspect_coverage, (root / settings["coverage"], settings["minimum_coverage"]))]
    checks += [("lint_" + str(index), inspect_lint, (root / path,)) for index, path in enumerate(config["lint"])]
    checks += [("manifest_" + str(index), inspect_manifest, (root / path, config["allowed_permissions"])) for index, path in enumerate(config["manifests"])]
    apk = config["apk"]
    checks += [("apk", inspect_apk, (root / apk["path"], apk["max_bytes"], apk["required_abis"])),
               ("metadata", inspect_metadata, (root / apk["metadata"], root / apk["path"], config["package_id"]))]
    if hygiene:
        checks.append(("hygiene", inspect_hygiene, (root,)))
    for name, check, arguments in checks:
        failures, details = check(*arguments)
        errors.extend(failures)
        evidence[name] = details
    return errors, evidence


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--config", type=Path, default=Path(".github/ci.yaml"))
    parser.add_argument("--report", type=Path, default=Path("android/build/reports/quality-gate.json"))
    parser.add_argument("--hygiene-only", action="store_true")
    args = parser.parse_args(argv)
    root = args.root.resolve()
    try:
        if args.hygiene_only:
            errors, evidence = inspect_hygiene(root)
        else:
            config = json.loads((root / args.config).read_text())
            errors, evidence = run(root, config)
    except (OSError, ValueError, KeyError, TypeError) as exc:
        errors, evidence = [f"invalid quality configuration: {exc}"], {}
    report = root / args.report
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps({"passed": not errors, "errors": errors, "evidence": evidence}, indent=2, ensure_ascii=False) + "\n")
    for error in errors:
        print("FAIL:", error, file=sys.stderr)
    if not errors:
        print("PASS: quality evidence meets all configured gates")
    print(f"Report: {report}")
    return int(bool(errors))


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Verify signed Android packages and publish the checked release bundle.

No Gradle invocation, local signing discovery, key generation, or secret upload.
The only private key accepted is the explicitly injected CI environment value.
"""
from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION_SOURCE = "android/app/build.gradle.kts"
SECRETS = (
    "INKOTP_SIGNING_KEYSTORE_BASE64", "INKOTP_SIGNING_STORE_PASSWORD",
    "INKOTP_SIGNING_KEY_ALIAS", "INKOTP_SIGNING_KEY_PASSWORD",
)
VERSION_PATTERN = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?"


LEGACY_PLATFORM_TAG = re.compile(
    r"(?:ios-v[0-9]+[.][0-9]+[.][0-9]+(?:-preview[.][0-9]+)?|macos-v"
    r"[0-9]+[.][0-9]+[.][0-9]+(?:-beta[.][0-9]+)?)"
)


class ReleaseError(Exception):
    """A controlled failure that is safe to show without printing credentials."""


def run(args: list[str], *, cwd: Path = ROOT) -> str:
    # Never print command arguments/stdout/stderr on failure: apksigner and gh
    # can echo values supplied to them. Passwords are passed by env reference.
    result = subprocess.run(args, cwd=cwd, capture_output=True, text=True, check=False)
    if result.returncode:
        raise ReleaseError(f"{Path(args[0]).name} failed (exit {result.returncode}); release stopped")
    return result.stdout


def load_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise ReleaseError(f"invalid JSON: {path.name}") from error


def config() -> dict:
    data = load_json(ROOT / ".github/release.yaml")
    if data.get("schema_version") != 1:
        raise ReleaseError("unsupported release configuration")
    normalize_certificate(data["certificate_sha256"])
    return data


def parse_gradle_version(text: str) -> dict:
    # Only literal assignments are allowed; never evaluate Gradle/source text.
    # Remove comments before counting assignments, and require exact RHS syntax.
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    codes = re.findall(r"\bversionCode\s*=\s*([^\n;}]+)", text)
    names = re.findall(r"\bversionName\s*=\s*([^\n;}]+)", text)
    if len(codes) != 1 or len(names) != 1:
        raise ReleaseError("versionCode/versionName must each have one literal assignment")
    code, name = codes[0].strip(), names[0].strip()
    if not re.fullmatch(r"[1-9][0-9]*", code) or int(code) > 2100000000:
        raise ReleaseError("invalid literal Android versionCode")
    match = re.fullmatch(f'"({VERSION_PATTERN})"', name)
    if not match:
        raise ReleaseError("invalid literal Android versionName")
    return {"version_name": match[1], "version_code": int(code)}


def validate_tag(tag: str, version: dict) -> bool:
    if not re.fullmatch("v" + VERSION_PATTERN, tag) or tag != "v" + version["version_name"]:
        raise ReleaseError("tag must exactly equal v{Android versionName}")
    return True


def validate_history(version: dict, tag: str, history: dict, releases: list,
                     legacy_tags: set[str] | frozenset[str] = frozenset()) -> None:
    for item in releases:
        prior_tag = item.get("tag_name")
        if prior_tag == tag:
            raise ReleaseError("release already exists, including drafts; overwrite is forbidden")
        if prior_tag not in history and prior_tag not in legacy_tags:
            raise ReleaseError("release history cannot be matched to a versioned source tag")
    for prior_tag, prior_version in history.items():
        if prior_tag != tag and prior_version["version_code"] >= version["version_code"]:
            raise ReleaseError("versionCode must increase beyond every previous version tag")


def preflight(tag: str, cfg: dict) -> dict:
    version = parse_gradle_version((ROOT / VERSION_SOURCE).read_text())
    validate_tag(tag, version)
    if version["version_code"] < cfg["minimum_version_code"]:
        raise ReleaseError("versionCode is below the installed beta compatibility floor")
    repository = os.environ.get("GITHUB_REPOSITORY", cfg["repository"])
    if repository != cfg["repository"]:
        raise ReleaseError("release repository does not match release configuration")
    commit = run(["git", "rev-parse", "HEAD"]).strip()
    tagged_commit = run(["git", "rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}"]).strip()
    if commit != tagged_commit:
        raise ReleaseError("checkout HEAD does not match the release tag")
    run(["git", "merge-base", "--is-ancestor", commit, "refs/remotes/origin/master"])
    legacy_tags = {old_tag for old_tag in run(
        ["git", "tag", "--list", "ios-v*", "macos-v*"]).splitlines()
        if LEGACY_PLATFORM_TAG.fullmatch(old_tag)}
    history = {}
    for old_tag in run(["git", "tag", "--list", "v*"]).splitlines():
        if not re.fullmatch("v" + VERSION_PATTERN, old_tag):
            raise ReleaseError("non-version release tag in history; manual reconciliation required")
        old_version = parse_gradle_version(run(["git", "show", f"refs/tags/{old_tag}:{VERSION_SOURCE}"]))
        validate_tag(old_tag, old_version)
        history[old_tag] = old_version
    # --slurp yields one array per page. Failure/auth/rate limits stop publishing.
    try:
        pages = json.loads(run(["gh", "api", "--paginate", "--slurp",
                               f"repos/{repository}/releases?per_page=100"]))
        releases = [item for page in pages for item in page]
        if not all(isinstance(item, dict) for item in releases):
            raise ValueError("invalid release list")
    except (ValueError, TypeError) as error:
        raise ReleaseError("invalid release history response") from error
    validate_history(version, tag, history, releases, legacy_tags)
    if not (ROOT / f"docs/releases/{version['version_name']}.md").is_file():
        raise ReleaseError("version-specific release notes are missing")
    return version


def validate_permissions(permissions: list[str], allowed: list[str]) -> None:
    unexpected = set(permissions) - set(allowed)
    if unexpected:
        raise ReleaseError("APK contains permissions outside the release allowlist")


def parse_badging(text: str) -> dict:
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", text, re.M)
    minimum = re.search(r"^sdkVersion:'([0-9]+)'$", text, re.M)
    target = re.search(r"^targetSdkVersion:'([0-9]+)'$", text, re.M)
    if not package or not minimum or not target:
        raise ReleaseError("aapt badging is missing package/version/SDK metadata")
    return {
        "application_id": package[1], "version_code": int(package[2]),
        "version_name": package[3], "min_sdk": int(minimum[1]), "target_sdk": int(target[1]),
        "debuggable": bool(re.search(r"^application-debuggable(?:\s|$)", text, re.M)),
        "permissions": re.findall(r"^uses-permission(?:-sdk-[0-9]+)?: name='([^']+)'", text, re.M),
    }


def validate_apk_identity(info: dict, version: dict, cfg: dict) -> None:
    for field in ("version_name", "version_code"):
        if info[field] != version[field]:
            raise ReleaseError(f"APK {field} does not match versioned source")
    for field in ("application_id", "min_sdk", "target_sdk"):
        if info[field] != cfg[field]:
            raise ReleaseError(f"APK {field} does not match release policy")
    if info["debuggable"]:
        raise ReleaseError("debuggable APK cannot be released")
    validate_permissions(info["permissions"], cfg["allowed_permissions"])


def validate_output_metadata(metadata: dict, version: dict, cfg: dict, apk: Path) -> None:
    elements = metadata.get("elements", [])
    if metadata.get("applicationId") != cfg["application_id"] or len(elements) != 1:
        raise ReleaseError("invalid Gradle output metadata application/element count")
    element = elements[0]
    if (element.get("versionCode") != version["version_code"] or
            element.get("versionName") != version["version_name"] or
            element.get("outputFile") != apk.name or element.get("filters")):
        raise ReleaseError("Gradle output metadata does not match version/source/universal APK")


def normalize_certificate(value: str) -> str:
    digest = value.replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ReleaseError("expected a SHA-256 signing certificate fingerprint")
    return digest


def validate_certificate(output: str, expected: str) -> str:
    signers = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([^\r\n]+)$", output, re.M)
    if len(signers) != 1 or "cn=android debug" in output.lower():
        raise ReleaseError("APK must have exactly one non-debug signing identity")
    digest = normalize_certificate(signers[0])
    if digest != normalize_certificate(expected):
        raise ReleaseError("APK signing certificate does not match the installed beta identity")
    return digest


def reject_signature_blocks(apk: Path) -> None:
    with zipfile.ZipFile(apk) as archive:
        if any(re.fullmatch(r"META-INF/[^/]+\.(?:RSA|DSA|EC|SF)", name, re.I) for name in archive.namelist()):
            raise ReleaseError("CI artifact is already signed (v1)")
    # The APK Signing Block immediately precedes the central directory (v2/v3).
    data = apk.read_bytes()
    eocd = data.rfind(b"PK\x05\x06", max(0, len(data) - 65557))
    if eocd < 0 or len(data) < eocd + 22:
        raise ReleaseError("invalid APK ZIP directory")
    central = struct.unpack_from("<I", data, eocd + 16)[0]
    if central >= 16 and data[central - 16:central] == b"APK Sig Block 42":
        raise ReleaseError("CI artifact is already signed (v2/v3)")


def required_secrets(environment: dict) -> bytes:
    if any(not environment.get(name) for name in SECRETS):
        raise ReleaseError("all four INKOTP_SIGNING_* Secrets are required; no key will be generated")
    try:
        key = base64.b64decode(environment[SECRETS[0]], validate=True)
    except (ValueError, binascii.Error) as error:
        raise ReleaseError("keystore Secret is not valid base64") from error
    if not key or len(key) > 1024 * 1024:
        raise ReleaseError("keystore Secret is empty or exceeds 1 MiB")
    return key


def inspect(apk: Path, version: dict, cfg: dict, build_tools: Path, *, signed: bool) -> dict:
    from quality_gate import inspect_apk
    errors, archive_info = inspect_apk(apk, cfg["max_apk_bytes"], cfg["required_abis"])
    if errors:
        raise ReleaseError("APK archive/ABI/ELF quality check failed: " + "; ".join(errors))
    info = parse_badging(run([str(build_tools / "aapt"), "dump", "badging", str(apk)]))
    validate_apk_identity(info, version, cfg)
    if signed:
        run([str(build_tools / "zipalign"), "-c", "-P", "16", "4", str(apk)])
        output = run([str(build_tools / "apksigner"), "verify", "--verbose", "--print-certs",
                      "--min-sdk-version", str(cfg["min_sdk"]), str(apk)])
        info["certificate_sha256"] = validate_certificate(output, cfg["certificate_sha256"])
    else:
        reject_signature_blocks(apk)
    return {**archive_info, **info}


def payload_names(version: dict) -> list[str]:
    return [f"InkOTP-{version['version_name']}.apk", "build-info.json", "release-notes.md"]


def release_notes(version: dict, context: dict) -> str:
    source = (ROOT / f"docs/releases/{version['version_name']}.md").read_text(encoding="utf-8")
    apk_name = payload_names(version)[0]
    run_url = f"https://github.com/{context['repository']}/actions/runs/{context['run_id']}"
    return (
        source.rstrip() + "\n\n## 下载\n\n"
        f"- Android · `{apk_name}`（Android 8.0 及以上，沿用发布签名）\n\n"
        f"`SHA256SUMS` 可核对发布文件。 [本次构建与测试记录]({run_url})\n"
    )


def safe_names(names: list[str]) -> None:
    if len(set(names)) != len(names) or any(not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", name) for name in names):
        raise ReleaseError("release payload must use unique, flat, safe filenames")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_checksums(directory: Path, names: list[str]) -> None:
    safe_names(names)
    (directory / "SHA256SUMS").write_text("".join(f"{sha256(directory / name)}  {name}\n" for name in names))


def verify_checksums(directory: Path, names: list[str]) -> None:
    expected = [*names, "SHA256SUMS"]
    safe_names(expected)
    actual = list(directory.iterdir())
    if any(item.is_symlink() or not item.is_file() for item in actual) or {item.name for item in actual} != set(expected):
        raise ReleaseError(f"{directory.name} contains unexpected or missing files")
    lines = (directory / "SHA256SUMS").read_text().splitlines()
    manifest = {}
    for line in lines:
        match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9][A-Za-z0-9_.-]*)", line)
        if not match or match[2] in manifest:
            raise ReleaseError("invalid checksum manifest")
        manifest[match[2]] = match[1]
    if set(manifest) != set(names) or any(sha256(directory / name) != manifest[name] for name in names):
        raise ReleaseError("bundle checksum mismatch")


def build_context() -> dict:
    commit = run(["git", "rev-parse", "HEAD"]).strip()
    context = {
        "commit": commit, "repository": os.environ.get("GITHUB_REPOSITORY", ""),
        "run_id": os.environ.get("GITHUB_RUN_ID", ""),
        "run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT", ""),
    }
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ReleaseError("invalid source commit")
    return context


def sign(tag: str, source: Path, directory: Path, build_tools: Path, key_path: Path, cfg: dict) -> None:
    # Validate all inputs before decoding Secrets or creating private files.
    version = parse_gradle_version((ROOT / VERSION_SOURCE).read_text())
    validate_tag(tag, version)
    apks = list(source.rglob("*.apk"))
    metadata = list(source.rglob("output-metadata.json"))
    if len(apks) != 1 or len(metadata) != 1 or apks[0].is_symlink() or metadata[0].is_symlink():
        raise ReleaseError("CI artifact must contain one unsigned APK and its output-metadata.json")
    validate_output_metadata(load_json(metadata[0]), version, cfg, apks[0])
    inspect(apks[0], version, cfg, build_tools, signed=False)
    runner_temp = Path(os.environ.get("RUNNER_TEMP", "/nonexistent-runner-temp")).resolve()
    if not key_path.resolve().is_relative_to(runner_temp) or key_path.exists():
        raise ReleaseError("keystore must be a new temporary runner file")
    key = required_secrets(os.environ)
    if directory.exists() and any(directory.iterdir()):
        raise ReleaseError("release output directory must be empty")
    directory.mkdir(parents=True, exist_ok=True)
    apk = directory / payload_names(version)[0]
    aligned = key_path.parent / "aligned.apk"
    try:
        # O_EXCL prevents replacing a file/symlink; mode is owner-only from creation.
        fd = os.open(key_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as stream:
            stream.write(key)
        run([str(build_tools / "zipalign"), "-P", "16", "4", str(apks[0]), str(aligned)])
        run([str(build_tools / "apksigner"), "sign", "--ks", str(key_path),
             "--ks-key-alias", os.environ[SECRETS[2]], "--ks-pass", "env:" + SECRETS[1],
             "--key-pass", "env:" + SECRETS[3], "--min-sdk-version", str(cfg["min_sdk"]),
             "--v1-signing-enabled", "false", "--v2-signing-enabled", "true",
             "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
             "--out", str(apk), str(aligned)])
        apk_info = inspect(apk, version, cfg, build_tools, signed=True)
    finally:
        key_path.unlink(missing_ok=True)
        aligned.unlink(missing_ok=True)
    context = build_context()
    info = {"schema_version": 1, "tag": tag, **version, **context,
            "apk": {"filename": apk.name, "sha256": sha256(apk), **apk_info},
            "tools": {"java": "25", "android_build_tools": cfg["build_tools"]},
            "hardware_validation": "未验证：系统认证/相机、覆盖升级与手机性能由用户真机测试"}
    (directory / "build-info.json").write_text(json.dumps(info, ensure_ascii=False, indent=2) + "\n")
    (directory / "release-notes.md").write_text(release_notes(version, context), encoding="utf-8")
    write_checksums(directory, payload_names(version))
    verify_checksums(directory, payload_names(version))


def verify_bundle(tag: str, directory: Path, build_tools: Path, cfg: dict) -> dict:
    version = parse_gradle_version((ROOT / VERSION_SOURCE).read_text())
    validate_tag(tag, version)
    verify_checksums(directory, payload_names(version))
    info = load_json(directory / "build-info.json")
    apk = directory / payload_names(version)[0]
    actual = inspect(apk, version, cfg, build_tools, signed=True)
    context = build_context()
    # A retry may reuse the successful signing job's artifact from the same run.
    attempt = info.get("run_attempt")
    current_attempt = context["run_attempt"]
    same_or_previous_attempt = (
        isinstance(attempt, str) and attempt.isdecimal() and current_attempt.isdecimal()
        and 0 < int(attempt) <= int(current_attempt)
    )
    if (not same_or_previous_attempt or info.get("tag") != tag or
            any(info.get(key) != value for key, value in version.items()) or
            any(info.get(key) != value for key, value in context.items() if key != "run_attempt") or
            info.get("apk", {}).get("sha256") != sha256(apk) or
            any(info.get("apk", {}).get(key) != value for key, value in actual.items())):
        raise ReleaseError("build-info does not match source/run/signed APK")
    if (directory / "release-notes.md").read_text(encoding="utf-8") != release_notes(version, context):
        raise ReleaseError("release notes differ from the versioned source and workflow run")
    return version


def publish_command(tag: str, version: dict, directory: Path, repository: str) -> list[str]:
    validate_tag(tag, version)
    args = ["gh", "release", "create", tag, "--repo", repository, "--verify-tag",
            "--title", "InkOTP " + version["version_name"],
            "--notes-file", str(directory / "release-notes.md")]
    if "-" in version["version_name"]:
        args.extend(["--prerelease", "--latest=false"])
    return args + [str(directory / name) for name in payload_names(version) + ["SHA256SUMS"]]


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["preflight", "sign", "verify-bundle", "publish"])
    parser.add_argument("--tag", required=True)
    parser.add_argument("--input-dir", type=Path, default=Path("unsigned"))
    parser.add_argument("--output-dir", type=Path, default=Path("release-bundle"))
    parser.add_argument("--build-tools", type=Path)
    parser.add_argument("--keystore-file", type=Path)
    args = parser.parse_args(argv)
    try:
        cfg = config()
        if args.command == "preflight":
            preflight(args.tag, cfg)
        else:
            if not args.build_tools or args.build_tools.name != cfg["build_tools"]:
                raise ReleaseError("the configured Android build-tools version is required")
            if args.command == "sign":
                if not args.keystore_file:
                    raise ReleaseError("temporary keystore path is required")
                sign(args.tag, args.input_dir, args.output_dir, args.build_tools, args.keystore_file, cfg)
            else:
                version = verify_bundle(args.tag, args.output_dir, args.build_tools, cfg)
                if args.command == "publish":
                    # gh uploads every file to a draft before making it public.
                    preflight(args.tag, cfg)
                    run(publish_command(args.tag, version, args.output_dir, cfg["repository"]))
        print(f"release {args.command}: passed")
        return 0
    except (ReleaseError, OSError, ValueError, KeyError, TypeError) as error:
        message = str(error) if isinstance(error, ReleaseError) else "invalid or unavailable release input"
        print(f"release blocked: {message}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

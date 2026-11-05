#!/usr/bin/env python3
"""Basic design-contract guard for InkOTP's Compose sources.

Rejects:
  * hard-coded colour literals ``Color(0x......)`` outside
    ``ui/theme/AppTokens.kt``;
  * hard-coded typography/radius literals ``fontSize = 12.sp`` and
    ``RoundedCornerShape(8.dp)`` outside the ``ui/theme`` package.

This is intentionally a simple line scanner, not a Kotlin parser.  Comments
whose first non-space characters are ``//``, ``*`` or ``/*`` are ignored so a
comment mentioning a token does not fail the build.

Usage:
  python tools/check_design_tokens.py --root android/app/src/main/kotlin
  python tools/check_design_tokens.py --root ... --allow '*Brand.kt'

Exit codes: 0 = clean (or source tree not created yet), 1 = violations,
2 = usage error.
"""

from __future__ import annotations

import argparse
import fnmatch
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_ROOT = ROOT / "android" / "app" / "src" / "main" / "kotlin"

COLOR_RE = re.compile(r"\bColor\s*\(\s*0x[0-9A-Fa-f]{6,8}\b\s*\)")
FONT_SIZE_RE = re.compile(r"\bfontSize\s*=\s*\d+(?:\.\d+)?\.sp\b")
RADIUS_RE = re.compile(r"\bRoundedCornerShape\s*\(\s*\d+(?:\.\d+)?\.dp\b")


def _display_path(path: Path) -> str:
    try:
        return path.resolve().relative_to(Path.cwd().resolve()).as_posix()
    except ValueError:
        return path.as_posix()


def _is_comment(line: str) -> bool:
    stripped = line.lstrip()
    return stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*")


def _allow_matches(path: Path, root: Path, patterns: list[str]) -> bool:
    candidates = {
        path.as_posix(),
        path.resolve().as_posix(),
        path.name,
    }
    try:
        candidates.add(path.resolve().relative_to(root.resolve()).as_posix())
    except ValueError:
        pass
    for pattern in patterns:
        if any(fnmatch.fnmatchcase(candidate, pattern) for candidate in candidates):
            return True
    return False


def _theme_relative(path: Path, root: Path) -> tuple[bool, bool]:
    """Return (is_theme_package, is_app_tokens)."""
    try:
        parts = path.resolve().relative_to(root.resolve()).parts
    except ValueError:
        parts = path.parts
    theme_package = any(
        parts[index] == "ui" and parts[index + 1] == "theme"
        for index in range(len(parts) - 1)
    )
    is_app_tokens = theme_package and path.name == "AppTokens.kt"
    return theme_package, is_app_tokens


def scan(root: Path, allow: list[str]) -> list[tuple[str, int, str]]:
    violations: list[tuple[str, int, str]] = []
    files = sorted(
        path for path in root.rglob("*") if path.is_file() and path.suffix in {".kt", ".kts"}
    )
    for path in files:
        if _allow_matches(path, root, allow):
            continue
        theme_package, is_app_tokens = _theme_relative(path, root)
        display = _display_path(path)
        try:
            lines = path.read_text(encoding="utf-8").splitlines()
        except (OSError, UnicodeDecodeError) as exc:
            violations.append((display, 0, f"cannot read file: {exc}"))
            continue
        for number, line in enumerate(lines, start=1):
            if _is_comment(line):
                continue
            if COLOR_RE.search(line) and not is_app_tokens:
                violations.append(
                    (display, number, "hard-coded Color(0x...) outside ui/theme/AppTokens.kt")
                )
            if FONT_SIZE_RE.search(line) and not theme_package:
                violations.append(
                    (display, number, "hard-coded fontSize = <number>.sp outside ui/theme")
                )
            if RADIUS_RE.search(line) and not theme_package:
                violations.append(
                    (display, number, "hard-coded RoundedCornerShape(<number>.dp) outside ui/theme")
                )
    return violations


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Reject hard-coded design tokens in Kotlin sources")
    parser.add_argument("--root", default=str(DEFAULT_ROOT), help="Kotlin source root")
    parser.add_argument(
        "--allow",
        action="append",
        default=[],
        metavar="GLOB",
        help="allow a file path/glob (repeatable; comma-separated values also accepted)",
    )
    args = parser.parse_args(argv)

    root = Path(args.root)
    allow: list[str] = []
    for value in args.allow:
        allow.extend(part.strip() for part in value.split(",") if part.strip())

    print(f"Design token scan: {root}")
    if not root.exists():
        print("warning: source root does not exist yet; scan skipped (M0 nascent tree)")
        print("PASS (skipped)")
        return 0
    if not root.is_dir():
        print(f"error: --root is not a directory: {root}", file=sys.stderr)
        return 2
    if allow:
        print("Allowed paths:")
        for pattern in allow:
            print(f"  - {pattern}")

    violations = scan(root, allow)
    if not violations:
        print("PASS: no hard-coded design token literals")
        return 0

    for path, line, message in violations:
        if line:
            print(f"{path}:{line}: {message}")
        else:
            print(f"{path}: {message}")
    affected = len({path for path, _, _ in violations})
    print(f"FAIL: {len(violations)} violation(s) in {affected} file(s)")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())

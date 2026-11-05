#!/usr/bin/env python3
"""Fetch Material Symbols Rounded SVGs and convert them to Android vector XML.

Development-time only: the generated vector drawables are committed, so the
app build never touches the network. Source symbols are Apache-2.0 (Google
Material Symbols).

Usage:
    python3 tools/fetch_material_symbols.py
"""

from __future__ import annotations

import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "android" / "app" / "src" / "main" / "res" / "drawable"
BASE = "https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsrounded/{name}/default/24px.svg"

ICONS = [
    "lock",
    "fingerprint",
    "shield",
    "add",
    "search",
    "settings",
    "delete",
    "content_copy",
    "more_vert",
    "more_horiz",
    "arrow_back",
    "visibility",
    "visibility_off",
    "photo_library",
    "qr_code_scanner",
    "flashlight_on",
    "upload",
    "download",
    "error",
    "check_circle",
    "info",
    "drag_indicator",
    "chevron_right",
    "close",
    "key",
    "backup",
    "restore",
    "schedule",
    "help",
    "palette",
    "check",
    "edit",
    "warning",
    "refresh",
    "security",
    "keyboard",
]

PATH_RE = re.compile(r"<path\b([^>]*)/>", re.S)
ATTR_RE = re.compile(r'([a-zA-Z:-]+)="([^"]*)"')


def attr_map(text: str) -> dict[str, str]:
    return {key: value for key, value in ATTR_RE.findall(text)}


def convert(name: str, svg: str) -> str:
    header = re.search(r"<svg\b([^>]*)>", svg, re.S)
    if header is None:
        raise ValueError(f"{name}: missing <svg>")
    view_box = attr_map(header.group(1)).get("viewBox", "0 0 24 24")
    parts = [float(value) for value in re.split(r"[ ,]+", view_box.strip())]
    if len(parts) != 4:
        raise ValueError(f"{name}: unsupported viewBox {view_box!r}")
    min_x, min_y, width, height = parts

    paths: list[str] = []
    for match in PATH_RE.finditer(svg):
        attrs = attr_map(match.group(1))
        data = attrs.get("d")
        if not data:
            continue
        fill_type = attrs.get("fill-rule")
        extra = ' android:fillType="evenOdd"' if fill_type == "evenodd" else ""
        paths.append(
            f'        <path android:fillColor="#FF000000" android:pathData="{data}"{extra} />'
        )
    if not paths:
        raise ValueError(f"{name}: no paths")

    translated = min_x != 0 or min_y != 0
    body = "\n".join(paths)
    if translated:
        transforms = []
        if min_x != 0:
            transforms.append(f'android:translateX="{-min_x:g}"')
        if min_y != 0:
            transforms.append(f'android:translateY="{-min_y:g}"')
        body = f'    <group {" ".join(transforms)}>\n{body}\n    </group>'

    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp"\n'
        '    android:height="24dp"\n'
        f'    android:viewportWidth="{width:g}"\n'
        f'    android:viewportHeight="{height:g}">\n'
        f"{body}\n"
        "</vector>\n"
    )


def main() -> int:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    failures = []
    for name in ICONS:
        url = BASE.format(name=name)
        try:
            with urllib.request.urlopen(url, timeout=30) as response:
                svg = response.read().decode("utf-8")
            xml = convert(name, svg)
            target = OUT_DIR / f"ic_{name}.xml"
            target.write_text(xml, encoding="utf-8")
            print(f"ok  {target.name}")
        except Exception as exc:  # noqa: BLE001 - dev script, report and continue
            failures.append((name, str(exc)))
            print(f"ERR {name}: {exc}", file=sys.stderr)
    if failures:
        print(f"{len(failures)} icon(s) failed", file=sys.stderr)
        return 1
    print(f"wrote {len(ICONS)} icons to {OUT_DIR}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

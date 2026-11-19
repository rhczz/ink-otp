#!/bin/bash
set -euo pipefail

if [[ $# != 3 ]]; then
    echo "Usage: assemble-app.sh BIN_DIR OUTPUT_DIR CRYPTO_LICENSES" >&2
    exit 2
fi

root="$(cd "$(dirname "$0")/../.." && pwd)"
bin_dir="$1"
output_dir="$2"
crypto_licenses="$3"
contents="$output_dir/InkOTP.app/Contents"
mkdir -p "$contents/MacOS" "$contents/Resources"
install -m 755 "$bin_dir/InkOTP" "$contents/MacOS/InkOTP"
install -m 644 "$root/macos/Resources/Info.plist" "$contents/Info.plist"
install -m 644 "$root/LICENSE" "$contents/Resources/LICENSE"
install -m 644 "$root"/macos/Resources/Privacy.*.md "$contents/Resources"
install -m 644 "$crypto_licenses" "$contents/Resources/ThirdPartyNotices.txt"

scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
icon_source="$root/website/assets/inkotp-icon.png"
iconset="$scratch/InkOTP.iconset"
mkdir "$iconset"
for size in 16 32 128 256 512; do
    sips -z "$size" "$size" "$icon_source" --out "$iconset/icon_${size}x${size}.png" >/dev/null
    doubled="$((size * 2))"
    sips -z "$doubled" "$doubled" "$icon_source" --out "$iconset/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns -o "$contents/Resources/AppIcon.icns" "$iconset"
/usr/libexec/PlistBuddy -c 'Add :CFBundleIconFile string AppIcon.icns' "$contents/Info.plist"

shopt -s nullglob
for bundle in "$bin_dir"/*.bundle; do
    ditto "$bundle" "$contents/Resources/$(basename "$bundle")"
done
for locale in "$root/macos/Resources"/*.lproj; do
    ditto "$locale" "$contents/Resources/$(basename "$locale")"
done

# The executable must carry only operating-system dylib references.
otool -L "$contents/MacOS/InkOTP" | awk '
NR > 1 && $1 !~ /^\/(usr\/lib|System\/Library)\// {
    print "Unexpected runtime dependency: " $1 > "/dev/stderr"; invalid = 1
} END {exit invalid}'
minos="$(vtool -show-build "$contents/MacOS/InkOTP" | awk '$1 == "minos" { print $2 }')"
[[ "$minos" =~ ^14[.]0([.]0)?$ ]]
plutil -lint "$contents/Info.plist"

#!/bin/bash
set -euo pipefail

if [[ $# != 4 ]]; then
    echo "Usage: package-universal.sh ARM_APP INTEL_APP OUTPUT_DIR VERSION" >&2
    exit 2
fi

root="$(cd "$(dirname "$0")/../.." && pwd)"
arm_app="$1"
intel_app="$2"
output="$(mkdir -p "$3" && cd "$3" && pwd)"
version="$4"

scratch="$(mktemp -d)"
keychain=""
mounted=""
cleanup() {
    if [[ -n "$mounted" ]]; then hdiutil detach "$mounted" -force >/dev/null 2>&1 || true; fi
    if [[ -n "$keychain" ]]; then security delete-keychain "$keychain" || true; fi
    rm -rf "$scratch"
}
trap cleanup EXIT

# Compare architecture-independent resources before lipo combines the executables.
diff -qr "$arm_app/Contents/Resources" "$intel_app/Contents/Resources"
cmp "$arm_app/Contents/Info.plist" "$intel_app/Contents/Info.plist"
stage="$scratch/image"
mkdir -p "$stage"
app="$stage/InkOTP.app"
ditto "$arm_app" "$app"
lipo -create "$arm_app/Contents/MacOS/InkOTP" "$intel_app/Contents/MacOS/InkOTP" -output "$scratch/InkOTP"
install -m 755 "$scratch/InkOTP" "$app/Contents/MacOS/InkOTP"
lipo -verify_arch arm64 x86_64 "$app/Contents/MacOS/InkOTP"
marketing_version="${version%%-beta.*}"
/usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $marketing_version" "$app/Contents/Info.plist"
/usr/libexec/PlistBuddy -c "Set :CFBundleVersion ${INKOTP_BUILD_NUMBER:-1}" "$app/Contents/Info.plist"
ln -s /Applications "$stage/Applications"

entitlements="$root/macos/Resources/InkOTP.entitlements"
dmg="$output/InkOTP-$version-macOS-universal.dmg"
signature="adhoc"
developer_id="${INKOTP_MACOS_DEVELOPER_ID:-}"
p12="${INKOTP_MACOS_P12_BASE64:-}"
p12_password="${INKOTP_MACOS_P12_PASSWORD:-}"
apple_id="${INKOTP_MACOS_NOTARY_APPLE_ID:-}"
team_id="${INKOTP_MACOS_TEAM_ID:-}"
notary_password="${INKOTP_MACOS_NOTARY_PASSWORD:-}"

if [[ -n "$developer_id$p12$p12_password$apple_id$team_id$notary_password" ]]; then
    if [[ "$developer_id" != "Developer ID Application: "* || -z "$p12" || -z "$p12_password" || -z "$apple_id" || -z "$team_id" || -z "$notary_password" ]]; then
        echo "Configure the complete Developer ID and notarization credentials." >&2
        exit 1
    fi
    keychain="$scratch/signing.keychain-db"
    keychain_password="$(openssl rand -hex 24)"
    security create-keychain -p "$keychain_password" "$keychain"
    security set-keychain-settings -lut 3600 "$keychain"
    security unlock-keychain -p "$keychain_password" "$keychain"
    printf '%s' "$p12" | base64 --decode > "$scratch/signing.p12"
    security import "$scratch/signing.p12" -k "$keychain" -P "$p12_password" -T /usr/bin/codesign
    security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$keychain_password" "$keychain"
    codesign --force --options runtime --timestamp --keychain "$keychain" --sign "$developer_id" --entitlements "$entitlements" "$app"
    signature="developer-id-notarized"
else
    if [[ "${INKOTP_REQUIRE_NOTARIZATION:-0}" == 1 ]]; then
        echo "Stable releases require Developer ID credentials and notarization." >&2
        exit 1
    fi
    codesign --force --options runtime --sign - --entitlements "$entitlements" "$app"
fi

codesign --verify --deep --strict "$app"
codesign -d --entitlements - "$app" > "$scratch/actual.plist"
python3 - "$entitlements" "$scratch/actual.plist" <<'PY'
import plistlib
import sys

with open(sys.argv[1], "rb") as expected, open(sys.argv[2], "rb") as actual:
    assert plistlib.load(expected) == plistlib.load(actual), "Unexpected application entitlements"
PY

if [[ "$signature" == "developer-id-notarized" ]]; then
    # Staple the app itself before constructing the image so offline installs can retain the ticket.
    ditto -c -k --keepParent "$app" "$scratch/notary.zip"
    xcrun notarytool submit "$scratch/notary.zip" --apple-id "$apple_id" --team-id "$team_id" --password "$notary_password" --wait
    xcrun stapler staple "$app"
    xcrun stapler validate "$app"
fi

hdiutil create -volname InkOTP -srcfolder "$stage" -format UDZO "$dmg"
if [[ "$signature" == "developer-id-notarized" ]]; then
    codesign --force --timestamp --keychain "$keychain" --sign "$developer_id" "$dmg"
    xcrun notarytool submit "$dmg" --apple-id "$apple_id" --team-id "$team_id" --password "$notary_password" --wait
    xcrun stapler staple "$dmg"
    xcrun stapler validate "$dmg"
    spctl --assess --type execute "$app"
fi
hdiutil verify "$dmg"
mkdir "$scratch/mounted"
hdiutil attach -nobrowse -readonly -mountpoint "$scratch/mounted" "$dmg"
mounted="$scratch/mounted"
codesign --verify --deep --strict "$mounted/InkOTP.app"
lipo -verify_arch arm64 x86_64 "$mounted/InkOTP.app/Contents/MacOS/InkOTP"
[[ "$(readlink "$mounted/Applications")" == /Applications ]]
hdiutil detach "$mounted"
mounted=""

printf '%s\n' "$signature" > "$output/signature.txt"
(cd "$output" && shasum -a 256 "$(basename "$dmg")" > SHA256SUMS.txt)

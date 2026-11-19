#!/usr/bin/env bash
set -euo pipefail
umask 077
: "${INKOTP_IOS_CERTIFICATE_BASE64:?Missing the Apple distribution certificate}"
: "${INKOTP_IOS_PROFILE_BASE64:?Missing the Ad Hoc provisioning profile}"
: "${INKOTP_IOS_CERTIFICATE_PASSWORD?Missing the distribution certificate password}"

private_dir=$(mktemp -d "$RUNNER_TEMP/inkotp-ios-sign.XXXXXX")
keychain="$private_dir/signing.keychain-db"
installed_profile=''
cleanup() {
  if [[ -n "$installed_profile" ]]; then rm -f "$installed_profile"; fi
  security delete-keychain "$keychain" >/dev/null 2>&1 || true
  rm -rf "$private_dir"
}
trap cleanup EXIT
keychain_password=$(openssl rand -hex 24)
printf %s "$INKOTP_IOS_CERTIFICATE_BASE64" | base64 -D > "$private_dir/certificate.p12"
printf %s "$INKOTP_IOS_PROFILE_BASE64" | base64 -D > "$private_dir/profile.mobileprovision"
security cms -D -i "$private_dir/profile.mobileprovision" -o "$private_dir/profile.plist"

python3 - "$private_dir" <<'PY'
import datetime
import plistlib
import sys
from pathlib import Path

directory = Path(sys.argv[1])
profile = plistlib.loads((directory / "profile.plist").read_bytes())
entitlements = profile["Entitlements"]
team, = profile["TeamIdentifier"]
identifier = "io.github.rhczz.inkotp"
if entitlements["application-identifier"] != f"{team}.{identifier}":
    raise SystemExit("Ad Hoc profile has a different application identifier")
if entitlements.get("get-task-allow") or not profile.get("ProvisionedDevices"):
    raise SystemExit("A distribution profile for registered devices is required")
if profile["ExpirationDate"] <= datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None):
    raise SystemExit("Provisioning profile has expired")
uuid = profile["UUID"]
(directory / "team").write_text(team)
(directory / "profile-id").write_text(uuid)
(directory / "export.plist").write_bytes(plistlib.dumps({
    "method": "release-testing",
    "signingStyle": "manual",
    "signingCertificate": "Apple Distribution",
    "teamID": team,
    "provisioningProfiles": {identifier: uuid},
    "stripSwiftSymbols": True,
}))
PY

security create-keychain -p "$keychain_password" "$keychain"
security unlock-keychain -p "$keychain_password" "$keychain"
security set-keychain-settings -lut 3600 "$keychain"
security import "$private_dir/certificate.p12" -k "$keychain" -P "$INKOTP_IOS_CERTIFICATE_PASSWORD" -T /usr/bin/codesign
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$keychain_password" "$keychain" >/dev/null
security list-keychains -d user -s "$keychain"
security find-identity -v -p codesigning "$keychain"
unset INKOTP_IOS_CERTIFICATE_BASE64 INKOTP_IOS_CERTIFICATE_PASSWORD INKOTP_IOS_PROFILE_BASE64

team=$(cat "$private_dir/team")
profile_id=$(cat "$private_dir/profile-id")
profile_dir="$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"
mkdir -p "$profile_dir"
installed_profile="$profile_dir/$profile_id.mobileprovision"
cp "$private_dir/profile.mobileprovision" "$installed_profile"

xcodebuild -project ios/InkOTP.xcodeproj -scheme InkOTP -configuration Release \
  -destination 'generic/platform=iOS' -archivePath "$private_dir/InkOTP.xcarchive" \
  "DEVELOPMENT_TEAM=$team" CODE_SIGN_STYLE=Manual 'CODE_SIGN_IDENTITY=Apple Distribution' \
  "PROVISIONING_PROFILE_SPECIFIER=$profile_id" \
  ${INKOTP_RELEASE_VERSION:+"INKOTP_RELEASE_VERSION=$INKOTP_RELEASE_VERSION"} archive

xcodebuild -exportArchive -archivePath "$private_dir/InkOTP.xcarchive" \
  -exportOptionsPlist "$private_dir/export.plist" -exportPath "$private_dir/export"
ipa="$private_dir/export/InkOTP.ipa"
test -f "$ipa"
ditto -x -k "$ipa" "$private_dir/unpacked"
codesign --verify --strict --deep "$private_dir/unpacked/Payload/InkOTP.app"
test -f "$private_dir/unpacked/Payload/InkOTP.app/embedded.mobileprovision"
mkdir -p ios/build/signed
cp "$ipa" ios/build/signed/InkOTP-iOS.ipa

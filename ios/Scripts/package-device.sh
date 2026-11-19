#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
cd "$script_dir/../.."

staging=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/inkotp-device-package.XXXXXX")
trap 'rm -rf "$staging"' EXIT
archive="$staging/InkOTP.xcarchive"
ipa="$staging/InkOTP-iOS-unsigned.ipa"

xcodebuild -project ios/InkOTP.xcodeproj -scheme InkOTP -configuration Release \
  -destination 'generic/platform=iOS' -derivedDataPath ios/build/DeviceDerivedData \
  -archivePath "$archive" CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO archive

app="$archive/Products/Applications/InkOTP.app"
test -f "$archive/Info.plist"
test -x "$app/InkOTP"
mkdir -p "$staging/Payload"
/usr/bin/ditto --norsrc "$app" "$staging/Payload/InkOTP.app"
/usr/bin/ditto -c -k --norsrc --keepParent "$staging/Payload" "$ipa"

bash "$script_dir/verify-device.sh" "$ipa" "$archive" > "$staging/device-package.json"
mkdir -p ios/build/packages ios/build/reports
mv "$ipa" ios/build/packages/InkOTP-iOS-unsigned.ipa
mv "$staging/device-package.json" ios/build/reports/device-package.json
cat ios/build/reports/device-package.json

#!/usr/bin/env bash
set -euo pipefail

current_archive=${1:?Specify the current CI simulator ZIP}
baseline_tag=ios-v0.5.3-preview.1
baseline_sha256=1791d9d65f73b5f98e82b549d04be666073711f8c0ebfe477657d943eb8e5391
bundle_id=io.github.rhczz.inkotp
reports="$PWD/ios/build/upgrade-reports"
work=$(mktemp -d "${RUNNER_TEMP:?GitHub Actions runner temp is required}/inkotp-upgrade.XXXXXX")
simulator=''
cleanup() {
  if [[ -n "$simulator" ]]; then
    xcrun simctl shutdown "$simulator" >/dev/null 2>&1 || true
    xcrun simctl delete "$simulator" >/dev/null 2>&1 || true
  fi
  rm -rf "$work"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

mkdir -p "$reports" "$work/previous" "$work/current"
curl --fail --silent --show-error --location --retry 3 --max-time 120 --proto '=https' --proto-redir '=https' \
  "https://github.com/rhczz/ink-otp/releases/download/$baseline_tag/InkOTP-iOS-Simulator.zip" \
  --output "$work/previous.zip"
printf '%s  %s\n' "$baseline_sha256" "$work/previous.zip" | shasum -a 256 -c
current_sha256=$(shasum -a 256 "$current_archive" | cut -d ' ' -f 1)
printf 'Baseline: %s\nBaseline GitHub asset SHA-256: %s\nCandidate CI ZIP SHA-256: %s\n' \
  "$baseline_tag" "$baseline_sha256" "$current_sha256" | tee "$reports/upgrade.txt"

ditto -x -k "$work/previous.zip" "$work/previous"
ditto -x -k "$current_archive" "$work/current"
previous_app="$work/previous/InkOTP.app"
current_app="$work/current/InkOTP.app"

value() {
  /usr/libexec/PlistBuddy -c "Print :$2" "$1/Info.plist"
}
for app in "$previous_app" "$current_app"; do
  test "$(value "$app" CFBundleIdentifier)" = "$bundle_id"
  test "$(value "$app" CFBundleExecutable)" = InkOTP
done
test "$(value "$previous_app" CFBundleShortVersionString)" = 0.5.3
previous_build=$(value "$previous_app" CFBundleVersion)
current_build=$(value "$current_app" CFBundleVersion)
[[ "$previous_build" == 1 && "$current_build" =~ ^[1-9][0-9]*$ && "$current_build" -gt "$previous_build" ]]
printf 'Expected CFBundleVersion: %s → %s\n' "$previous_build" "$current_build" >> "$reports/upgrade.txt"

template=$(python3 ios/Scripts/select-simulator.py --family iPad)
configuration=$(xcrun simctl list devices available --json | python3 -c '
import json
import sys

for runtime, devices in json.load(sys.stdin)["devices"].items():
    for device in devices:
        if device["udid"] == sys.argv[1]:
            print(device["deviceTypeIdentifier"], runtime)
            raise SystemExit(0)
raise SystemExit("No configuration found for the selected iPad simulator")
' "$template")
read -r device_type runtime <<< "$configuration"
simulator=$(xcrun simctl create "InkOTP iPad upgrade ${GITHUB_RUN_ID:-CI}" "$device_type" "$runtime")
xcrun simctl boot "$simulator"
xcrun simctl bootstatus "$simulator" -b
printf 'Simulator: %s (%s, %s)\n' "$simulator" "$device_type" "$runtime" >> "$reports/upgrade.txt"

destination="platform=iOS Simulator,id=$simulator"
derived="$work/DerivedData"
xcodebuild -project ios/InkOTP.xcodeproj -scheme InkOTPUpgrade -configuration Debug \
  -destination "$destination" -derivedDataPath "$derived" CODE_SIGN_IDENTITY=- build-for-testing
products="$derived/Build/Products"
runner=$(python3 ios/Scripts/prepare-upgrade-xctestrun.py "$products")
xcrun simctl install "$simulator" "$runner"

# The candidate must already have a valid simulator signature from the
# packaging job. Both releases are installed directly from their ZIPs.
codesign --verify --strict "$current_app"

verify_installed() {
  local app=$1 build=$2 installed
  installed=$(xcrun simctl get_app_container "$simulator" "$bundle_id" app)
  test "$(value "$installed" CFBundleVersion)" = "$build"
  cmp "$installed/InkOTP" "$app/InkOTP"
}
data_container() {
  xcrun simctl get_app_container "$simulator" "$bundle_id" data
}
test_phase() {
  local phase=$1 identifier=$2
  local result="$reports/$phase.xcresult" summary="$reports/$phase.json"
  xcodebuild test-without-building -xctestrun "$products/upgrade-preinstalled.xctestrun" \
    -destination "$destination" -parallel-testing-enabled NO -disable-concurrent-destination-testing \
    "-only-testing:InkOTPUpgradeUITests/$identifier" -resultBundlePath "$result"
  xcrun xcresulttool get test-results summary --path "$result" > "$summary"
  python3 - "$summary" <<'PY'
import json
import sys
from pathlib import Path

summary = json.loads(Path(sys.argv[1]).read_bytes())
if summary.get("totalTestCount") != 1 or summary.get("passedTests") != 1:
    raise SystemExit("Expected exactly one passing test for this upgrade phase")
PY
}

xcrun simctl install "$simulator" "$previous_app"
verify_installed "$previous_app" "$previous_build"
original_container=$(data_container)
test_phase baseline InkOTPFlowTests/testConsentSetupAddSearchAndReopenVault
verify_installed "$previous_app" "$previous_build"
test "$(data_container)" = "$original_container"
vault="$original_container/Library/Application Support/InkOTP/vault-v1.json"
test -s "$vault"
cp "$vault" "$work/baseline-vault.json"
xcrun simctl terminate "$simulator" "$bundle_id" >/dev/null 2>&1 || true

# Install the candidate over the old bundle without removing its data or keys.
xcrun simctl install "$simulator" "$current_app"
verify_installed "$current_app" "$current_build"
test "$(data_container)" = "$original_container"
cmp "$work/baseline-vault.json" "$vault"
test_phase upgraded InkOTPUpgradeTests/testReadsExistingAccountWithDeviceKeyAfterUpgrade
verify_installed "$current_app" "$current_build"
test "$(data_container)" = "$original_container"

printf '%s\n' 'PASS: Same simulator and data container. In-place install preserved ciphertext and the upgraded app read the account using the existing Keychain.' | tee -a "$reports/upgrade.txt"
cat "$reports/upgrade.txt" >> "$GITHUB_STEP_SUMMARY"

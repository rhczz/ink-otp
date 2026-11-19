#!/usr/bin/env bash
set -euo pipefail

current_archive=${1:?Specify the current CI simulator ZIP}
baseline_tag=ios-v0.5.3-preview.1
baseline_commit=c465eeafbde02fd9e983c2138750d5a60460b08c
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
published_app="$work/previous/InkOTP.app"
current_app="$work/current/InkOTP.app"

value() {
  /usr/libexec/PlistBuddy -c "Print :$2" "$1/Info.plist"
}


has_simulator_entitlements() {
  local app=$1 architecture=$2
  xcrun otool -arch "$architecture" -l "$app/InkOTP" | awk '
    $1 == "sectname" { section = $2 }
    $1 == "segname" && $2 == "__TEXT" && section == "__entitlements" { xml = 1 }
    $1 == "segname" && $2 == "__TEXT" && section == "__ents_der" { der = 1 }
    $1 == "segname" { section = "" }
    END { exit !(xml && der) }
  '
}

# Simulator Keychain reads link-time entitlements. Signing an already linked
# executable cannot add these sections, so the unsigned preview needs a rebuild.
runner_arch=$(uname -m)
previous_app=$published_app
if ! has_simulator_entitlements "$published_app" "$runner_arch" ||
   ! codesign --verify --strict "$published_app"; then
  test "$(git rev-parse "refs/tags/$baseline_tag^{commit}")" = "$baseline_commit"
  source_tree="$work/baseline-source"
  baseline_build="$work/BaselineDerivedData"
  printf '%s\n' 'Published ZIP verified, but its app lacks simulator Keychain signing. Rebuilding the pinned release source for the upgrade baseline.' \
    | tee -a "$reports/upgrade.txt"
  printf 'Baseline rebuild commit: %s\n' "$baseline_commit" >> "$reports/upgrade.txt"
  mkdir -p "$source_tree"
  git archive "$baseline_commit" ios | tar -xf - -C "$source_tree"
  xcodegen generate --spec "$source_tree/ios/project.yml" --project "$source_tree/ios"
  xcodebuild -project "$source_tree/ios/InkOTP.xcodeproj" -scheme InkOTP -configuration Release \
    -destination 'generic/platform=iOS Simulator' -derivedDataPath "$baseline_build" \
    ONLY_ACTIVE_ARCH=NO "ARCHS=$runner_arch" CODE_SIGN_IDENTITY=- build
  previous_app="$baseline_build/Build/Products/Release-iphonesimulator/InkOTP.app"
else
  printf '%s\n' 'Using the published baseline app directly.' >> "$reports/upgrade.txt"
fi

has_simulator_entitlements "$previous_app" "$runner_arch"
codesign --verify --strict "$previous_app"
for architecture in arm64 x86_64; do
  if ! has_simulator_entitlements "$current_app" "$architecture"; then
    echo "::error::Candidate is missing simulator Keychain entitlements for $architecture."
    exit 1
  fi
done
codesign --verify --strict "$current_app"
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

verify_installed() {
  local app=$1 build=$2 installed
  installed=$(xcrun simctl get_app_container "$simulator" "$bundle_id" app)
  test "$(value "$installed" CFBundleVersion)" = "$build"
  cmp "$installed/InkOTP" "$app/InkOTP"
}
data_container() {
  xcrun simctl get_app_container "$simulator" "$bundle_id" data
}
vault_path() {
  printf '%s/Library/Application Support/InkOTP/vault-v1.json\n' "$1"
}
report_container() {
  local phase=$1 before=$2 after=$3
  if [[ "$after" == "$before" ]]; then
    printf '%s: data container path unchanged\n' "$phase" >> "$reports/upgrade.txt"
  else
    printf '%s: data container path moved\n' "$phase" >> "$reports/upgrade.txt"
  fi
}
test_phase() {
  local phase=$1 identifier=$2 app=$3 plan
  local result="$reports/$phase.xcresult" summary="$reports/$phase.json"
  # Simulator XCTest may install its target. Give it the exact package already installed for this phase.
  plan=$(python3 ios/Scripts/prepare-upgrade-xctestrun.py "$products" "$app")
  xcodebuild test-without-building -xctestrun "$plan" \
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
first_container=$(data_container)
test_phase baseline InkOTPFlowTests/testConsentSetupAddSearchAndReopenVault "$previous_app"
verify_installed "$previous_app" "$previous_build"
baseline_container=$(data_container)
report_container Baseline "$first_container" "$baseline_container"
baseline_vault=$(vault_path "$baseline_container")
test -s "$baseline_vault"
cp "$baseline_vault" "$work/baseline-vault.json"
xcrun simctl terminate "$simulator" "$bundle_id" >/dev/null 2>&1 || true

# The system may move the container during installation. Before the new app
# launches, verify that it received the original randomized ciphertext.
xcrun simctl install "$simulator" "$current_app"
verify_installed "$current_app" "$current_build"
installed_container=$(data_container)
report_container Installation "$baseline_container" "$installed_container"
cmp "$work/baseline-vault.json" "$(vault_path "$installed_container")"
test_phase upgraded InkOTPUpgradeTests/testReadsExistingAccountWithDeviceKeyAfterUpgrade "$current_app"
verify_installed "$current_app" "$current_build"
tested_container=$(data_container)
report_container Reopen "$installed_container" "$tested_container"

printf '%s\n' 'PASS: Same simulator. In-place install preserved ciphertext and the upgraded app read the account using the existing Keychain.' | tee -a "$reports/upgrade.txt"

# The candidate from the ZIP must also be able to create and reopen a fresh
# vault after the system removes its prior private application data.
xcrun simctl uninstall "$simulator" "$bundle_id"
xcrun simctl install "$simulator" "$current_app"
verify_installed "$current_app" "$current_build"
fresh_container=$(data_container)
if [[ -e "$(vault_path "$fresh_container")" ]]; then
  echo '::error::Reinstall must start without the previous private vault.'
  exit 1
fi
test_phase reinstalled InkOTPFlowTests/testConsentSetupAddSearchAndReopenVault "$current_app"
verify_installed "$current_app" "$current_build"
fresh_vault=$(vault_path "$(data_container)")
test -s "$fresh_vault"
if cmp -s "$work/baseline-vault.json" "$fresh_vault"; then
  echo '::error::Reinstall must create a separate encrypted vault.'
  exit 1
fi

printf '%s\n' 'PASS: Candidate ZIP reinstalled directly, created a fresh vault, and reopened it with the simulator Keychain.' | tee -a "$reports/upgrade.txt"
cat "$reports/upgrade.txt" >> "$GITHUB_STEP_SUMMARY"

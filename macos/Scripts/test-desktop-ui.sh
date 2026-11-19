#!/bin/bash
set -euo pipefail

if [[ $# != 2 || "$2" != /*.xcresult ]]; then
    echo "Usage: test-desktop-ui.sh CANDIDATE_DMG ABSOLUTE_CANDIDATE_XCRESULT" >&2
    exit 2
fi
candidate="$1"
result="$2"
[[ -f "$candidate" ]]
mkdir -p "$(dirname "$result")"

root="$(cd "$(dirname "$0")/../.." && pwd)"
scratch="$(mktemp -d)"
mount="$scratch/installer"
staged="$scratch/apps/InkOTP.app"
mounted=false
cleanup() {
    if [[ "$mounted" == true ]]; then hdiutil detach "$mount" || true; fi
    rm -rf "$scratch"
}
trap cleanup EXIT

# Xcode must use a genuine desktop session. This only inspects the runner.
if [[ "$(stat -f %Su /dev/console)" != "$(id -un)" ]] || ! launchctl print "gui/$(id -u)" >/dev/null 2>&1; then
    echo "The Actions runner has no GUI session for its user. XCTest cannot drive the macOS desktop." >&2
    exit 1
fi
command -v xcodegen >/dev/null
mkdir -p "$mount" "$(dirname "$staged")"
ditto "$root/macos/UITests" "$scratch/UITests"
(
    cd "$scratch/UITests"
    INKOTP_UI_STAGED_APP="$staged" xcodegen generate --spec project.yml
)

baseline="$scratch/InkOTP-0.1.0-beta.1-macOS-universal.dmg"
baseline_url="https://github.com/rhczz/ink-otp/releases/download/macos-v0.1.0-beta.1/InkOTP-0.1.0-beta.1-macOS-universal.dmg"
baseline_digest="04d945c11ff4bb7cad255d8900c27a90796f96fe9bcba72c77a62cd4dda308bd"
curl --fail --silent --show-error --location --retry 3 --max-time 180 "$baseline_url" --output "$baseline"
printf '%s  %s\n' "$baseline_digest" "$baseline" | shasum -a 256 -c -

stage_bundle() {
    local image="$1" version
    hdiutil verify "$image"
    hdiutil attach -readonly -nobrowse -mountpoint "$mount" "$image"
    mounted=true
    codesign --verify --strict --deep "$mount/InkOTP.app"
    if [[ -d "$staged" ]]; then
        mv "$staged" "$scratch/replaced-InkOTP.app"
    fi
    ditto "$mount/InkOTP.app" "$staged"
    hdiutil detach "$mount"
    mounted=false
    codesign --verify --strict --deep "$staged"
    [[ "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$staged/Contents/Info.plist")" == io.github.rhczz.inkotp.macos ]]
    version="$(/usr/libexec/PlistBuddy -c 'Print :InkOTPReleaseVersion' "$staged/Contents/Info.plist")"
    printf 'Staged InkOTP %s at %s\n' "$version" "$staged"
    staged_version="$version"
}

run_ui_test() {
    local method="$1" bundle="$2"
    xcodebuild test -project "$scratch/UITests/InkOTPDesktopUI.xcodeproj" -scheme InkOTPDesktopUI \
        -destination 'platform=macOS' -derivedDataPath "$scratch/build" -parallel-testing-enabled NO \
        -only-testing:"InkOTPDesktopUITests/DesktopUpgradeTests/$method" -resultBundlePath "$bundle"
    # Require evidence that the selected test ran and passed. An empty selection cannot pass.
    xcrun xcresulttool get test-results summary --path "$bundle" > "${bundle%.xcresult}.summary.json"
    python3 - "${bundle%.xcresult}.summary.json" <<'PY'
import json
import sys
from pathlib import Path

report = json.loads(Path(sys.argv[1]).read_text())
if report.get("totalTestCount") != 1 or report.get("passedTests") != 1:
    raise SystemExit("Expected one passed desktop XCTest in the result bundle")
PY
}

stage_bundle "$baseline"
[[ "$staged_version" == 0.1.0-beta.1 ]]
run_ui_test testPublishedBetaCreatesVaultAndAccount "${result%.xcresult}-beta1.xcresult"

stage_bundle "$candidate"
[[ "$staged_version" != 0.1.0-beta.1 ]]
run_ui_test testCandidatePreservesAccountAndDesktopBehavior "$result"

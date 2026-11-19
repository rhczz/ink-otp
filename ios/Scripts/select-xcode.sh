#!/usr/bin/env bash
set -euo pipefail
for app in /Applications/Xcode_26*.app; do
  if [[ -d "$app/Contents/Developer" ]]; then
    selected="$app"
  fi
done
if [[ -z "${selected:-}" ]]; then
  echo "::error::No Xcode 26 installation is available on this GitHub runner."
  exit 1
fi
echo "DEVELOPER_DIR=$selected/Contents/Developer" >> "$GITHUB_ENV"
DEVELOPER_DIR="$selected/Contents/Developer" xcodebuild -version

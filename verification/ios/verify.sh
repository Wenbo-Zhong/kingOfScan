#!/bin/bash
set -euo pipefail
verification_device="${1:?Usage: bash verification/ios/verify.sh SIMULATOR_UUID [OUTPUT_DIRECTORY]}"
verification_root="$(cd "$(dirname "$0")/../.." && pwd)"
verification_output="${2:-/tmp/kingscan-ios-verification}"
mkdir -p "$verification_output"
verification_output="$(cd "$verification_output" && pwd)"
verification_app="$verification_output/OfflineVerification.app"
mkdir -p "$verification_app"
cd "$verification_root"
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64 -PkingscanVerification=true > "$verification_output/framework-build.log" 2>&1
cp verification/ios/Info.plist "$verification_app/Info.plist"
xcrun --sdk iphonesimulator swiftc verification/ios/OfflineHost.swift \
    -F composeApp/build/bin/iosSimulatorArm64/debugFramework -framework ComposeApp -Xlinker -dead_strip \
    -o "$verification_app/OfflineVerification" -target arm64-apple-ios17.2-simulator \
    > "$verification_output/host-build.log" 2>&1
xcrun simctl install "$verification_device" "$verification_app"
verification_launch_status=0
xcrun simctl launch --console --terminate-running-process "$verification_device" com.kingscan.offline-verification \
    > "$verification_output/runtime.log" 2>&1 || verification_launch_status=$?
verification_container="$(xcrun simctl get_app_container "$verification_device" com.kingscan.offline-verification data)"
cp -R "$verification_container/Documents/evidence" "$verification_output/"
verification_pass_count="$(rg -c '^PASS ' "$verification_output/evidence/result.txt" || true)"
if [ "$verification_launch_status" -ne 0 ] || [ "$verification_pass_count" != "7" ] || rg -q '^FAIL' "$verification_output/evidence/result.txt"; then
    cat "$verification_output/evidence/result.txt"
    exit 1
fi
cat "$verification_output/evidence/result.txt"

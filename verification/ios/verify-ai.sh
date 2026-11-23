#!/bin/bash
set -euo pipefail
ai_device="${1:?Usage: bash verification/ios/verify-ai.sh SIMULATOR_UUID FIXTURES [--skip-framework]}"
ai_fixtures="${2:?Provide the prepared real-image reference fixtures}"
ai_repo="$(cd "$(dirname "$0")/../.." && pwd)"
ai_output="/tmp/kingscan-ai-ios-host"
ai_app="$ai_output/OfflineVerification.app"
mkdir -p "$ai_app"
cd "$ai_repo"
if [ "${3:-}" != "--skip-framework" ]; then
    ./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64 -PkingscanVerification=true > "$ai_output/framework-build.log" 2>&1
fi
cp verification/ios/Info.plist "$ai_app/Info.plist"
cp iosApp/iosApp/AIModels/models.json "$ai_app/models.json"
ai_manifest_changed=false
if ! cmp -s iosApp/iosApp/AIModels/models.json "$ai_output/compiled-models.json"; then
    ai_manifest_changed=true
fi
for ai_model in fingers docres esdnet handwriting watermark; do
    if [ "$ai_manifest_changed" = true ]; then
        rm -rf "$ai_app/$ai_model.mlmodelc"
    fi
    if [ ! -d "$ai_app/$ai_model.mlmodelc" ]; then
        xcrun coremlcompiler compile "iosApp/iosApp/AIModels/$ai_model.mlpackage" "$ai_app" >> "$ai_output/resources-build.log" 2>&1
    fi
done
cp iosApp/iosApp/AIModels/models.json "$ai_output/compiled-models.json"
xcrun --sdk iphonesimulator swiftc verification/ios/DocumentAiHost.swift \
    -F composeApp/build/bin/iosSimulatorArm64/debugFramework -framework ComposeApp -Xlinker -dead_strip \
    -o "$ai_app/OfflineVerification" -target arm64-apple-ios17.2-simulator > "$ai_output/host-build.log" 2>&1
xcrun simctl install "$ai_device" "$ai_app"
ai_container="$(xcrun simctl get_app_container "$ai_device" com.kingscan.offline-verification data)"
mkdir -p "$ai_container/Documents/ai-evidence"
cp -R "$ai_fixtures/." "$ai_container/Documents/ai-evidence/"
ai_status=0
xcrun simctl launch --console --terminate-running-process "$ai_device" com.kingscan.offline-verification \
    > "$ai_output/runtime.log" 2>&1 || ai_status=$?
mkdir -p "$ai_output/evidence"
cp -R "$ai_container/Documents/ai-evidence/." "$ai_output/evidence/"
cat "$ai_output/evidence/result.txt"
if [ "$ai_status" -ne 0 ] || rg -q '^FAIL' "$ai_output/evidence/result.txt"; then exit 1; fi

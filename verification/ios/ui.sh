#!/bin/bash
set -euo pipefail
verification_device="${1:?Usage: bash verification/ios/ui.sh SIMULATOR_UUID [OUTPUT_DIRECTORY]}"
verification_root="$(cd "$(dirname "$0")/../.." && pwd)"
verification_output="${2:-/tmp/kingscan-ios-ui}"
mkdir -p "$verification_output"
verification_output="$(cd "$verification_output" && pwd)"
cd "$verification_root"
xcodebuild -project iosApp/iosApp.xcodeproj -scheme KingScanAcceptance -configuration Debug \
    -sdk iphonesimulator -destination "id=$verification_device" \
    -derivedDataPath "$verification_output/build" CODE_SIGNING_ALLOWED=NO build-for-testing \
    > "$verification_output/build.log" 2>&1
verification_app="$verification_output/build/Build/Products/Debug-iphonesimulator/KingScan.app"
xcrun simctl terminate "$verification_device" com.kingscan.app 2>/dev/null || true
xcrun simctl install "$verification_device" "$verification_app"
verification_container="$(xcrun simctl get_app_container "$verification_device" com.kingscan.app data)"
python3 - "$verification_container" "$verification_root" <<'PY'
import json, pathlib, shutil, sys
documents = pathlib.Path(sys.argv[1]) / "Documents/kingscan/documents"
shutil.rmtree(documents / "local-ui-acceptance", ignore_errors=True)
root = documents / "localuiacceptance"
pages = root / "pages"
pages.mkdir(parents=True, exist_ok=True)
fixtures = pathlib.Path(sys.argv[2]) / "verification/fixtures"
metadata = []
for index, name in enumerate(["retouch-input.jpg", "ocr-input.jpg"]):
    filename = f"ui-page-{index}.jpg"
    shutil.copyfile(fixtures / name, pages / filename)
    shutil.copyfile(fixtures / name, pages / f"original-{filename}")
    metadata.append(dict(id=f"ui-page-{index}", fileName=filename, width=1000 if index == 0 else 1200,
                         height=700 if index == 0 else 1600, filter="Original",
                         originalFileName=f"original-{filename}", edit=dict(filter="Original")))
(root / "meta.json").write_text(json.dumps(dict(id="localuiacceptance", title="UI验收文档",
    createdAt=1795400000000, updatedAt=1795400000000, pages=metadata), ensure_ascii=False))
PY
verification_status=0
xcodebuild -project iosApp/iosApp.xcodeproj -scheme KingScanAcceptance -configuration Debug \
    -destination "id=$verification_device" -derivedDataPath "$verification_output/build" \
    -resultBundlePath "$verification_output/result.xcresult" CODE_SIGNING_ALLOWED=NO \
    test-without-building > "$verification_output/runtime.log" 2>&1 || verification_status=$?
xcrun xcresulttool export attachments --path "$verification_output/result.xcresult" \
    --output-path "$verification_output/screenshots" > "$verification_output/export.log" 2>&1
exit "$verification_status"

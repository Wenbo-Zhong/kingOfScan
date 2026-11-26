"""Collect measured results, source snapshots and completed build logs."""
import argparse
from collections import Counter
import hashlib
import json
import math
import re
import shutil
import tarfile
import xml.etree.ElementTree as ET
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--before", type=Path, required=True)
parser.add_argument("--after", type=Path, required=True)
parser.add_argument("--comparison", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--apk", type=Path, required=True)
args = parser.parse_args()
repo = Path(__file__).resolve().parents[2]
args.output.mkdir(parents=True, exist_ok=True)


def rows(root):
    return [json.loads(line) for line in (root / "results.jsonl").read_text().splitlines()
            if line.startswith("{")]


def area(quad):
    return abs(sum(a[0] * b[1] - a[1] * b[0] for a, b in zip(quad, quad[1:] + quad[:1]))) / 2


def stability(sequence):
    visible = [r for r in sequence if r["quad"] is not None]
    steps, changes = [], []
    for a, b in zip(sequence, sequence[1:]):
        if a["quad"] is not None and b["quad"] is not None:
            steps.append(max(math.dist(p, q) for p, q in zip(a["quad"], b["quad"])))
            changes.append(abs(area(a["quad"]) / area(b["quad"]) - 1))
    return {
        "visible_frames": len(visible), "total_frames": len(sequence),
        "first_visible_ms": next(r["index"] * 125 for r in visible),
        "first_confirmed_ms": next(r["index"] * 125 for r in sequence if r["fresh"]),
        "max_corner_step": max(steps),
        "corner_steps_over_0_08": sum(s > 0.08 for s in steps),
        "area_steps_over_35_percent": sum(s > 0.35 for s in changes),
        "area_min": min(area(r["quad"]) for r in visible),
        "area_max": max(area(r["quad"]) for r in visible),
    }


before, after = rows(args.before), rows(args.after)
before_manifest = json.loads((args.before / "manifest.json").read_text())
after_manifest = json.loads((args.after / "manifest.json").read_text())
assert before_manifest["input_sha256"] == after_manifest["input_sha256"]
assert before_manifest["frames"] == after_manifest["frames"]
before_frames = [r for r in before if r["kind"] == "replay"]
after_frames = [r for r in after if r["kind"] == "replay"]
assert len(before_frames) == len(after_frames) == 48
assert before_frames[2:] == after_frames[2:]
rotations = [r for r in after if r["kind"] == "rotation"]
assert rotations == [r for r in before if r["kind"] == "rotation"]
new_stability = stability(after_frames)
assert new_stability["corner_steps_over_0_08"] == new_stability["area_steps_over_35_percent"] == 0
android_before = json.loads((args.before / "android.json").read_text())
android_after = json.loads((args.after / "android.json").read_text())
assert android_before["debuggable"] == android_after["debuggable"] is True
assert android_before["device"] == android_after["device"]
assert not android_before["acquisition"][0]["visible"]
assert android_after["acquisition"][0]["visible"] and not android_after["acquisition"][0]["fresh"]
assert android_after["acquisition"][2]["fresh"]
assert android_after["analysis"]["median_ms"] < android_before["analysis"]["median_ms"]
tests = {}
for suite in ("testDebugUnitTest", "iosSimulatorArm64Test"):
    data = [ET.parse(p).getroot().attrib
            for p in (repo / "composeApp/build/test-results" / suite).glob("TEST-*.xml")]
    assert data
    tests[suite] = {key: sum(int(r[key]) for r in data)
                    for key in ("tests", "failures", "errors", "skipped")}
    assert tests[suite]["failures"] == tests[suite]["errors"] == tests[suite]["skipped"] == 0

original = Path("/Users/bytedance/Desktop/Screen_recording_20261125_230103.mp4")
source_hashes = {}
for source_set, relative in [
    ("commonMain", "core/imaging/BinaryMorphology.kt"),
    ("commonMain", "core/imaging/DocumentEdges.kt"),
    ("commonMain", "core/imaging/CapturePreviewAnalyzer.kt"),
    ("androidMain", "core/platform/AndroidCamera.kt"),
]:
    path = repo / "composeApp/src" / source_set / "kotlin/com/kingscan/app" / relative
    source_hashes[str(path.relative_to(repo))] = hashlib.sha256(path.read_bytes()).hexdigest()
report = {
    "date": "2026-11-26",
    "inputs": {
        "screenshot": before_manifest["input"], "screenshot_sha256": before_manifest["input_sha256"],
        "original_video": str(original), "original_video_sha256": hashlib.sha256(original.read_bytes()).hexdigest(),
        "processed_frames_sha256": before_manifest["frames"],
        "limitations": "The 48 inputs are previously masked/inpainted screen-recording crops, not raw camera frames. Timing uses the retained screenshot converted to grayscale and scaled to 360x480.",
    },
    "android_debug_runtime": {"before": android_before, "after": android_after},
    "desktop_jvm": json.loads((args.comparison / "timings.json").read_text()),
    "stability": {"before": stability(before_frames), "after": new_stability,
                  "confirmed_frames_identical": True, "rotation_coordinates_identical": True},
    "rotations": rotations,
    "frames": {"before": before_frames, "after": after_frames},
    "tests": tests,
    "source_sha256": source_hashes,
    "before_source_manifest": before_manifest,
    "after_source_manifest": after_manifest,
    "apk": {"path": str(args.apk.relative_to(repo)), "bytes": args.apk.stat().st_size,
            "sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest()},
    "device_validation": "Android API 36 ARM64 emulator. Physical phone unavailable over ADB; no CamScanner same-input or physical-camera end-to-end timing comparison.",
}
samples = json.loads((args.before / "profile.json").read_text())["recording"]["events"]
hotspots = Counter()
for event in samples:
    frames = ((event.get("values") or {}).get("stackTrace") or {}).get("frames") or []
    methods = [f["method"]["type"]["name"] + "." + f["method"]["name"] for f in frames]
    if any("kingscan" in method for method in methods):
        hotspots.update(methods[:1])
report["baseline_desktop_cpu_samples"] = dict(hotspots.most_common(12))
report["apk"]["bundle_check"] = json.loads(Path("/tmp/kingscan-latency-bundle.json").read_text())
package_flags = next(line for line in Path("/tmp/kingscan-latency-release-package.log")
                     .read_text().splitlines() if "pkgFlags=[" in line)
assert "DEBUGGABLE" not in package_flags
report["apk"]["debuggable"] = False
digest_pattern = r"certificate SHA-256 digest: ([0-9a-f]+)"
previous_certificate = re.search(digest_pattern, Path("/tmp/kingscan-latency-installed-certificate.log").read_text())
new_certificate = re.search(digest_pattern, Path("/tmp/kingscan-latency-signature.log").read_text())
assert previous_certificate and new_certificate
report["apk"]["signing_certificate_matches_previous_debug"] = previous_certificate[1] == new_certificate[1]
assert report["apk"]["signing_certificate_matches_previous_debug"]
report["apk"]["emulator_install_success"] = "Success" in Path("/tmp/kingscan-latency-release-install.log").read_text()
report["apk"]["emulator_launch_success"] = "Status: ok" in Path("/tmp/kingscan-latency-release-launch.log").read_text()
assert report["apk"]["emulator_install_success"] and report["apk"]["emulator_launch_success"]
(args.output / "results.json").write_text(json.dumps(report, indent=2) + "\n")
common = "composeApp/src/commonMain/kotlin/com/kingscan/app/"
selected = {
    common + "core/document/Geometry.kt", common + "core/imaging/RgbaImage.kt",
    *(common + "core/imaging/" + name for name in
      ("DocumentEdges.kt", "DocumentDetector.kt", "CaptureAnalysis.kt", "CapturePreviewAnalyzer.kt", "PerspectiveWarp.kt")),
    "composeApp/src/androidMain/kotlin/com/kingscan/app/core/platform/AndroidCamera.kt",
}
with tarfile.open("/tmp/kingscan-outline-prerepair-20261126.tar.gz") as original_archive:
    with tarfile.open(args.output / "before-sources.tar.gz", "w:gz") as archive:
        for name in sorted(selected):
            entry = original_archive.getmember(name)
            archive.addfile(entry, original_archive.extractfile(entry))
logs = args.output / "logs"
logs.mkdir(exist_ok=True)
for name in (
    "kingscan-latency-baseline-native.log", "kingscan-latency-final-native.log",
    "kingscan-latency-final-tests-build.log", "kingscan-latency-release-build.log",
    "kingscan-latency-signature.log", "kingscan-latency-alignment.log",
    "kingscan-latency-release-install.log", "kingscan-latency-release-launch.log",
):
    path = Path("/tmp") / name
    assert path.is_file(), path
    shutil.copyfile(path, logs / name)
for root, label in ((args.before, "before"), (args.after, "after")):
    shutil.copyfile(root / "results.jsonl", args.output / f"{label}.jsonl")
print(json.dumps({"tests": tests, "stability": report["stability"], "apk": report["apk"]}, indent=2))

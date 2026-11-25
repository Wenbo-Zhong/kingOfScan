"""Create screenshot evidence using the production commonMain Kotlin detector.

Requires Python 3 + Pillow, JDK 17+, and cached Kotlin compiler 2.1.21.
python replay.py --screenshot /path/Screenshot.png --java /path/to/java
The screenshot is never edited. The extracted camera view still contains UI.
"""
from pathlib import Path
import argparse
import hashlib
import json
import math
import subprocess
import tempfile
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw


def run(command, log):
    result = subprocess.run(command, capture_output=True)
    log.write_bytes(result.stdout + result.stderr)
    if result.returncode:
        raise RuntimeError(f"Command failed; see {log}")
    return result.stdout.decode()


def polygon_area(points):
    return abs(sum(a[0] * b[1] - a[1] * b[0]
                   for a, b in zip(points, points[1:] + points[:1]))) * 0.5


def cross(a, b, p):
    return (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0])


def intersection(a, b, c, d):
    ab = (b[0] - a[0], b[1] - a[1])
    cd = (d[0] - c[0], d[1] - c[1])
    denominator = ab[0] * cd[1] - ab[1] * cd[0]
    assert abs(denominator) > 1e-6
    delta = (c[0] - a[0], c[1] - a[1])
    t = (delta[0] * cd[1] - delta[1] * cd[0]) / denominator
    return [a[0] + t * ab[0], a[1] + t * ab[1]]


def overlap(subject, clip):
    output = subject
    for a, b in zip(clip, clip[1:] + clip[:1]):
        source, output = output, []
        if not source:
            return 0.0
        previous = source[-1]
        for current in source:
            current_inside = cross(a, b, current) >= 0
            previous_inside = cross(a, b, previous) >= 0
            if current_inside != previous_inside:
                output.append(intersection(previous, current, a, b))
            if current_inside:
                output.append(current)
            previous = current
    return polygon_area(output)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--screenshot", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--before-jar", type=Path,
                        help="Optional preserved JVM jar containing the old detector + Replay.kt")
    parser.add_argument("--before-ref", help="Compile a previous Git revision for the comparison")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    source = repo / "composeApp/src/commonMain/kotlin/com/kingscan/app"
    output = args.output or repo / "verification/evidence/document-edges"
    output.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="kingscan-edge-replay-"))
    original = Image.open(args.screenshot).convert("RGB")
    assert original.size == (1080, 2400), "This reference is for the supplied 1080x2400 screenshot"
    camera_box = (0, 480, 1080, 1920)
    camera = original.crop(camera_box)
    camera.save(output / "camera-screenshot-crop.png")
    images = [output / "camera-screenshot-crop.png"]
    for degrees in (90, 180, 270):
        path = work / f"screenshot-cw-{degrees}.png"
        camera.rotate(-degrees, expand=True).save(path)
        images.append(path)

    def jar(group, name, version):
        cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
        choices = [p for p in (cache / group / name / version).rglob("*.jar")
                   if not p.name.endswith(("-sources.jar", "-all.jar"))]
        assert len(choices) == 1, choices
        return str(choices[0])

    stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.1.21")
    compiler_cp = [
        jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.1.21"), stdlib,
        jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2"),
        jar("org.jetbrains", "annotations", "13.0"),
        jar("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"),
    ]
    geometry = (source / "model/Models.kt").read_text()
    geometry = geometry[geometry.index("@Serializable\ndata class PointF"):
                        geometry.index("@Serializable\ndata class ImageAdjustments")]
    (work / "Geometry.kt").write_text("package com.kingscan.app.model\n" + geometry.replace("@Serializable\n", ""))
    rgba = (source / "imaging/RgbaImage.kt").read_text()
    rgba = rgba[:rgba.index("expect fun createImageCodec()")].replace(
        "import androidx.compose.ui.graphics.ImageBitmap\n", "")
    (work / "RgbaImage.kt").write_text(rgba)
    production = [source / "imaging/DocumentDetector.kt", source / "imaging/DocumentEdges.kt"]
    replay_jar = work / "detector.jar"
    def compile_detector(paths, destination, label):
        run([args.java, "-cp", ":".join(compiler_cp),
             "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
             "-classpath", stdlib, "-d", str(destination),
             *map(str, paths), str(work / "Geometry.kt"), str(work / "RgbaImage.kt"),
             str(Path(__file__).with_name("Replay.kt"))], work / f"compile-{label}.log")

    compile_detector(production, replay_jar, "after")
    before_sources = {}
    before_jar = args.before_jar
    if args.before_ref:
        before_dir = work / "before"
        before_dir.mkdir()
        for path in production:
            result = subprocess.run(["git", "show", f"{args.before_ref}:{path.relative_to(repo)}"],
                                    cwd=repo, capture_output=True)
            if result.returncode:
                if path.name == "DocumentEdges.kt":
                    continue  # The old detector did not have this file.
                raise RuntimeError(result.stderr.decode())
            target = before_dir / path.name
            target.write_bytes(result.stdout)
            before_sources[target.name] = hashlib.sha256(result.stdout).hexdigest()
        before_jar = work / "before.jar"
        compile_detector(list(before_dir.glob("*.kt")), before_jar, "before")

    def replay(path, images, label):
        text = run([args.java, "-cp", f"{path}:{stdlib}", "ReplayKt", *map(str, images)],
                   work / f"{label}.log")
        return [json.loads(line) for line in text.splitlines() if line.startswith("{")]

    after = replay(replay_jar, images, "after")
    before = replay(before_jar, images[:1], "before")[0] if before_jar else None

    # Manually selected endpoints on each visible straight side, in camera pixels.
    # Intersections extend the straight sides through the rounded corners/notch.
    reference_sides = [
        [[236, 513], [786, 500]],  # top
        [[878, 590], [927, 925]],  # right
        [[235, 1063], [845, 1018]],  # bottom
        [[140, 594], [132, 932]],  # left (outside the notch)
    ]
    reference = [intersection(*reference_sides[(i + 3) % 4], *reference_sides[i]) for i in range(4)]

    def measurements(result):
        if result is None or result["quad"] is None:
            return None
        pixels = [[x * camera.width, y * camera.height] for x, y in result["quad"]]
        shared = overlap(pixels, reference)
        errors = [math.dist(a, b) for a, b in zip(pixels, reference)]
        return dict(quad_pixels=pixels, approximate_reference_iou=shared / (
            polygon_area(pixels) + polygon_area(reference) - shared),
            corner_error_pixels=errors, mean_corner_error_pixels=sum(errors) / 4)

    checks = []
    for path in (repo / "composeApp/build/test-results/testDebugUnitTest").glob("TEST-*.xml"):
        suite = ET.parse(path).getroot()
        checks.append(dict(name=suite.attrib["name"], tests=int(suite.attrib["tests"]),
                           failures=int(suite.attrib["failures"]), errors=int(suite.attrib["errors"])))
    report = dict(
        source_sha256={p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in production},
        before_ref=args.before_ref, before_source_sha256=before_sources,
        input_sha256=hashlib.sha256(args.screenshot.read_bytes()).hexdigest(),
        input_dimensions=list(original.size), camera_crop=list(camera_box),
        reference_method="Approximate manually chosen straight-side extensions; not a calibrated ground truth",
        reference_sides_pixels=reference_sides, reference_quad_pixels=reference,
        before=before, after=after, before_measurements=measurements(before),
        after_measurements=measurements(after[0]), shared_tests=checks,
        limitations=["Screenshot replay contains existing UI overlay, not raw camera frames",
                     "Timing is desktop JVM, not mobile performance",
                     "No same-frame CamScanner comparison was performed"],
    )
    orientation_checks = []
    rotated_reference = reference
    rotated_width, rotated_height = camera.size
    for index, result in enumerate(after):
        if index:
            turned = [[rotated_height - 1 - y, x] for x, y in rotated_reference]
            rotated_reference = turned[-1:] + turned[:-1]
            rotated_width, rotated_height = rotated_height, rotated_width
        assert result["quad"] is not None, f"Document missed at {index * 90} degrees"
        pixels = [[x * rotated_width, y * rotated_height] for x, y in result["quad"]]
        shared = overlap(pixels, rotated_reference)
        iou = shared / (polygon_area(pixels) + polygon_area(rotated_reference) - shared)
        errors = [math.dist(a, b) for a, b in zip(pixels, rotated_reference)]
        orientation_checks.append(dict(clockwise_degrees=index * 90, approximate_reference_iou=iou,
                                       mean_corner_error_pixels=sum(errors) / 4))
        assert iou > 0.97, f"Outer frame IoU {iou:.4f} at {index * 90} degrees"
    report["orientation_checks"] = orientation_checks
    (output / "replay-results.json").write_text(json.dumps(report, indent=2) + "\n")

    compare = Image.new("RGB", (camera.width * 2, camera.height + 72), "#161c24")
    draw = ImageDraw.Draw(compare)
    for column, (label, result, color) in enumerate([
        ("BEFORE: DESK SELECTED", before, "#ff5353"),
        ("AFTER: DOCUMENT OUTLINE", after[0], "#00e897"),
    ]):
        offset = column * camera.width
        compare.paste(camera, (offset, 72))
        draw.text((offset + 28, 25), label, fill=color, font_size=28)
        if result and result["quad"]:
            points = [(offset + x * camera.width, 72 + y * camera.height) for x, y in result["quad"]]
            draw.line(points + points[:1], fill=color, width=6)
            for x, y in points:
                draw.ellipse((x - 9, y - 9, x + 9, y + 9), fill=color)
    compare.save(output / "before-after.png")
    print(json.dumps(report, indent=2))
    print(f"Evidence saved in {output}; compiler logs in {work}")


if __name__ == "__main__":
    main()

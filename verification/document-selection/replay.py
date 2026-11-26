"""Replay the two 2026-11-26 screenshots using actual commonMain Kotlin sources.

Requires Pillow, opencv-python-headless and cached Kotlin 2.1.21 compiler jars.
All build intermediates go to a temporary directory; --output holds evidence.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import subprocess
import tempfile

import cv2
import numpy as np
from PIL import Image, ImageDraw


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def area(points):
    return abs(sum(a[0] * b[1] - a[1] * b[0]
                   for a, b in zip(points, points[1:] + points[:1]))) / 2


def cross(a, b, p):
    return (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0])


def intersection(a, b, c, d):
    ab = (b[0] - a[0], b[1] - a[1])
    cd = (d[0] - c[0], d[1] - c[1])
    denominator = ab[0] * cd[1] - ab[1] * cd[0]
    assert abs(denominator) > 1e-8
    delta = (c[0] - a[0], c[1] - a[1])
    t = (delta[0] * cd[1] - delta[1] * cd[0]) / denominator
    return [a[0] + t * ab[0], a[1] + t * ab[1]]


def overlap(subject, clip):
    output = subject
    for a, b in zip(clip, clip[1:] + clip[:1]):
        source, output = output, []
        if not source:
            return 0
        previous = source[-1]
        for current in source:
            current_inside = cross(a, b, current) >= 0
            previous_inside = cross(a, b, previous) >= 0
            if current_inside != previous_inside:
                output.append(intersection(previous, current, a, b))
            if current_inside:
                output.append(current)
            previous = current
    return area(output)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--ours", required=True, type=Path)
    p.add_argument("--comparison", required=True, type=Path)
    p.add_argument("--before-root", type=Path)
    p.add_argument("--output", required=True, type=Path)
    p.add_argument("--java", default="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java")
    args = p.parse_args()
    repo = Path(__file__).resolve().parents[2]
    source = repo / "composeApp/src/commonMain/kotlin/com/kingscan/app"
    out = args.output
    out.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="kingscan-selection-replay-"))
    cache = Path.home() / ".gradle/caches/modules-2/files-2.1"

    def jar(group, name, version):
        choices = [f for f in (cache / group / name / version).rglob("*.jar")
                   if not f.name.endswith(("-sources.jar", "-all.jar"))]
        assert len(choices) == 1, choices
        return str(choices[0])

    std = jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.1.21")
    compiler = [jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.1.21"), std,
                jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2"),
                jar("org.jetbrains", "annotations", "13.0"),
                jar("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330")]
    report = {"inputs": {}, "sources": {}, "limitations": [
        "Camera crops contain UI; stroke removal is an approximation, not a raw camera frame.",
        "Manual straight-side references are approximate, not calibrated ground truth.",
        "The two applications' screenshots have different camera positions and capture times.",
        "This replay does not measure camera startup, focusing, or UI drawing latency.",
    ]}
    images = []
    crops = {"ours": (0, 480, 1080, 1920), "comparison": (0, 346, 1080, 1790)}
    for name, original in [("ours", args.ours), ("comparison", args.comparison)]:
        image = Image.open(original).convert("RGB")
        assert image.size == (1080, 2400)
        camera = image.crop(crops[name])
        path = out / f"{name}.png"
        camera.save(path)
        images.append(path)
        rgb = np.array(camera)
        r, g, b = [rgb[:, :, i].astype(int) for i in range(3)]
        if name == "ours":
            mask = ((r > 140) & (r > g * 1.4) & (g > b * 1.3)).astype(np.uint8) * 255
            for x, y in [(332, 167), (816, 199), (758, 489), (206, 399)]:
                cv2.circle(mask, (x, y), 16, 255, -1)
        else:
            mask = ((g > 100) & (g > r * 1.4) & (b > r * 1.4)).astype(np.uint8) * 255
        mask = cv2.dilate(mask, np.ones((5, 5), np.uint8))
        cleaned = cv2.inpaint(rgb, mask, 7, cv2.INPAINT_TELEA)
        clean_path = out / f"{name}-stroke-removed.png"
        Image.fromarray(cleaned).save(clean_path)
        Image.fromarray(mask).save(out / f"{name}-stroke-mask.png")
        images.append(clean_path)
        report["inputs"][name] = {
            "original_sha256": digest(original), "original_name": original.name,
            "camera_crop": list(crops[name]), "crop_sha256": digest(path),
            "stroke_removed_sha256": digest(clean_path),
            "mask_sha256": digest(out / f"{name}-stroke-mask.png"),
            "stroke_removal": "RGB color mask, 5x5 dilation, Telea inpaint radius 7; UI fill remains",
        }

    def replay(root, label):
        build = work / label
        build.mkdir()
        (build / "Geometry.kt").write_text((root / "core/document/Geometry.kt").read_text().replace(
            "import kotlinx.serialization.Serializable\n", "").replace("@Serializable\n", ""))
        (build / "RgbaImage.kt").write_text((root / "core/imaging/RgbaImage.kt").read_text().split(
            "expect fun createImageCodec()")[0].replace("import androidx.compose.ui.graphics.ImageBitmap\n", ""))
        production = [root / "core/imaging" / n for n in
                      ("DocumentDetector.kt", "DocumentEdges.kt", "BinaryMorphology.kt")]
        report["sources"][label] = {f.name: digest(f) for f in production}
        with (build / "compile.log").open("w") as log:
            subprocess.run([args.java, "-cp", ":".join(compiler),
                            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
                            "-classpath", std, "-d", str(build / "detector.jar"),
                            *map(str, production), str(build / "Geometry.kt"), str(build / "RgbaImage.kt"),
                            str(Path(__file__).with_name("Sweep.kt"))],
                           stdout=log, stderr=subprocess.STDOUT, check=True)
        with (out / f"{label}.jsonl").open("w") as log:
            subprocess.run([args.java, "-cp", f"{build / 'detector.jar'}:{std}", "SweepKt",
                            *map(str, images)], stdout=log, stderr=subprocess.STDOUT, check=True)
        return [json.loads(line) for line in (out / f"{label}.jsonl").read_text().splitlines()]

    before = replay(args.before_root, "before") if args.before_root else []
    after = replay(source, "after")
    # Endpoints picked on visible long edges, extended through rounded corners.
    sides = {
        "ours": [[[320, 658], [670, 642]], [[788, 720], [812, 935]],
                 [[270, 1035], [760, 1024]], [[230, 808], [211, 960]]],
        "comparison": [[[330, 743], [720, 726]], [[762, 800], [787, 1000]],
                       [[300, 1086], [730, 1081]], [[258, 850], [237, 1010]]],
    }
    report["reference_sides_pixels"] = sides
    checks = []
    for result in after:
        name = "ours" if result["file"].startswith("ours") else "comparison"
        reference = [intersection(*sides[name][(i + 3) % 4], *sides[name][i]) for i in range(4)]
        width, height = Image.open(out / result["file"]).size
        for _ in range(result["degrees"] // 90):
            reference = [[height - 1 - y, x] for x, y in reference]
            reference = reference[-1:] + reference[:-1]
            width, height = height, width
        assert result["quad"] is not None, result
        detected = [[x * width, y * height] for x, y in result["quad"]]
        shared = overlap(detected, reference)
        iou = shared / (area(detected) + area(reference) - shared)
        mean_error = sum(math.dist(a, b) for a, b in zip(detected, reference)) / 4
        checks.append({**result, "approximate_iou": iou, "mean_corner_error_pixels": mean_error})
        assert iou > (0.96 if name == "ours" else 0.93), checks[-1]
        assert mean_error < 20, checks[-1]
    report["checks"] = checks
    report["before"] = before
    report["after"] = after
    comparison = Image.new("RGB", (2160, 1512), "#161c24")
    draw = ImageDraw.Draw(comparison)
    for column, (label, rows, color) in enumerate([
        ("OLD DETECTOR REPLAY", before, "#ff5353"),
        ("UPDATED DETECTOR REPLAY", after, "#00e897"),
    ]):
        offset = column * 1080
        comparison.paste(Image.open(out / "ours.png"), (offset, 72))
        draw.text((offset + 28, 10), label, fill=color, font_size=28)
        draw.text((offset + 28, 45), "Orange outline is retained screenshot UI", fill="#c8ced5", font_size=18)
        result = next((r for r in rows if r["file"] == "ours.png" and r["degrees"] == 0 and
                       not r.get("preferred_background")), None)
        if result and result["quad"]:
            polygon = [(offset + x * 1080, 72 + y * 1440) for x, y in result["quad"]]
            draw.line(polygon + polygon[:1], fill=color, width=7)
    comparison.save(out / "before-after.png")
    (out / "replay-results.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"Passed {len(checks)} screenshot checks; evidence: {out}; build logs: {work}")


if __name__ == "__main__":
    main()

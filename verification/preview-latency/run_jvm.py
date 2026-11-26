"""Compile actual shared production sources and replay identical retained inputs."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--output", required=True, type=Path)
parser.add_argument("--frames", type=Path)
parser.add_argument("--source-root", type=Path)
parser.add_argument("--profile", action="store_true")
args = parser.parse_args()
repo = Path(__file__).resolve().parents[2]
source = args.source_root or repo / "composeApp/src/commonMain/kotlin/com/kingscan/app"
args.output.mkdir(parents=True, exist_ok=True)
cache = Path.home() / ".gradle/caches/modules-2/files-2.1"


def jar(group, name, version):
    choices = [p for p in (cache / group / name / version).rglob("*.jar")
               if not p.name.endswith(("-sources.jar", "-all.jar"))]
    assert len(choices) == 1, choices
    return str(choices[0])


stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.1.21")
compiler = [
    jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.1.21"),
    stdlib,
    jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2"),
    jar("org.jetbrains", "annotations", "13.0"),
    jar("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"),
]
geometry = (source / "core/document/Geometry.kt").read_text().replace(
    "import kotlinx.serialization.Serializable\n", "").replace("@Serializable\n", "")
(args.output / "Geometry.kt").write_text(geometry)
rgba = (source / "core/imaging/RgbaImage.kt").read_text()
rgba = rgba[:rgba.index("expect fun createImageCodec()")].replace(
    "import androidx.compose.ui.graphics.ImageBitmap\n", "")
(args.output / "RgbaImage.kt").write_text(rgba)
names = ["DocumentDetector.kt", "DocumentEdges.kt", "CaptureAnalysis.kt",
         "CapturePreviewAnalyzer.kt", "PerspectiveWarp.kt"]
if (source / "core/imaging/BinaryMorphology.kt").exists():
    names.append("BinaryMorphology.kt")
production = [source / "core/imaging" / name for name in names]
java = "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java"
with (args.output / "compile.log").open("w") as log:
    subprocess.run([
        java, "-cp", ":".join(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-classpath", stdlib, "-d", str(args.output / "replay.jar"),
        *map(str, production), str(args.output / "Geometry.kt"), str(args.output / "RgbaImage.kt"),
        str(Path(__file__).with_name("LatencyReplay.kt")),
    ], stdout=log, stderr=subprocess.STDOUT, check=True)
input_path = repo / "verification/evidence/document-edges/camera-screenshot-crop.png"
manifest = {
    "sources_sha256": {str(p.relative_to(source)): hashlib.sha256(p.read_bytes()).hexdigest()
                       for p in production},
    "input": str(input_path),
    "input_sha256": hashlib.sha256(input_path.read_bytes()).hexdigest(),
    "frames": {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
               for p in sorted(args.frames.glob("*.png"))} if args.frames else {},
}
(args.output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
command = [java]
if args.profile:
    command += [f"-XX:StartFlightRecording=filename={args.output / 'profile.jfr'},settings=profile,dumponexit=true"]
command += ["-cp", f"{args.output / 'replay.jar'}:{stdlib}", "LatencyReplayKt", str(input_path)]
if args.frames:
    command.append(str(args.frames))
with (args.output / "results.jsonl").open("w") as log:
    subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
print(args.output)

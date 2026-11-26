"""Alternate preserved before/after jars without competing builds or profilers."""
import argparse
import json
import statistics
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--before", type=Path, required=True)
parser.add_argument("--after", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
repo = Path(__file__).resolve().parents[2]
cache = Path.home() / ".gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.1.21"
stdlibs = [p for p in cache.rglob("*.jar") if not p.name.endswith(("-sources.jar", "-all.jar"))]
assert len(stdlibs) == 1
java = "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java"
results = {"before": [], "after": []}
for index, label in enumerate(["before", "after", "after", "before"]):
    root = getattr(args, label)
    command = [java, "-cp", f"{root / 'replay.jar'}:{stdlibs[0]}", "LatencyReplayKt",
               str(repo / "verification/evidence/document-edges/camera-screenshot-crop.png")]
    path = args.output / f"{index}-{label}.jsonl"
    with path.open("w") as log:
        subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
    rows = [json.loads(line) for line in path.read_text().splitlines() if line.startswith("{")]
    results[label].append({r["stage"]: r for r in rows if r["kind"] == "timing"})
report = {
    "method": "before, after, after, before; separate JVMs, 20 warmups and 60 samples per stage; no JFR or concurrent builds",
    "runs": results,
    "summary": {
        label: {stage: {metric: statistics.mean(run[stage][metric] for run in runs)
                        for metric in ("median_ms", "p95_ms")}
                for stage in ("detector", "analysis")}
        for label, runs in results.items()
    },
}
(args.output / "timings.json").write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report["summary"], indent=2))

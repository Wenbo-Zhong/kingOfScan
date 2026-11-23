"""Preserve the actual Gradle XML suite results after the final shared regression run."""
from pathlib import Path
import json
import xml.etree.ElementTree as ET

repo = Path(__file__).resolve().parents[2]
report = {}
for target in ["testDebugUnitTest", "iosSimulatorArm64Test"]:
    files = sorted((repo / "composeApp/build/test-results" / target).glob("*.xml"))
    assert files, target
    suites = [ET.parse(file).getroot().attrib for file in files]
    assert all(int(suite.get("failures", 0)) == 0 and int(suite.get("errors", 0)) == 0
               and int(suite.get("skipped", 0)) == 0 for suite in suites), suites
    report[target] = suites
    print(target, sum(int(suite["tests"]) for suite in suites), "passed")
(repo / "verification/evidence/ai-test-results.json").write_text(json.dumps(report, indent=2))

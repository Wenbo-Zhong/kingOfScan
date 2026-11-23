"""Drive production Android UI and an independent URI sender on an owned test emulator."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("--serial", default="emulator-5554")
parser.add_argument("--output", default="/tmp/kingscan-android-ui")
args = parser.parse_args()
output = Path(args.output)
output.mkdir(parents=True, exist_ok=True)
fixtures = Path(__file__).resolve().parents[1] / "fixtures"
adb_path = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk")) / "platform-tools/adb"
adb = [str(adb_path), "-s", args.serial]


def command(*parts, binary=False, data=None):
    result = subprocess.run(adb + list(parts), input=data, capture_output=True, check=True)
    if binary:
        return result.stdout
    return result.stdout.decode(errors="replace")


def snapshot():
    command("shell", "uiautomator", "dump", "/sdcard/kingscan-acceptance.xml")
    raw = command("exec-out", "cat", "/sdcard/kingscan-acceptance.xml")
    (output / "last-ui.xml").write_text(raw)
    return list(ET.fromstring(raw).iter("node"))


def find(label=None, desc=None, nodes=None):
    for node in snapshot() if nodes is None else nodes:
        if ((label is not None and node.get("text") == label) or
                (desc is not None and node.get("content-desc") == desc)):
            return node
    return None


def wait(label=None, desc=None, timeout=20):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        node = find(label, desc)
        if node is not None:
            return node
    raise AssertionError(f"UI element missing: {label or desc}")


def rect(node):
    return tuple(map(int, re.findall(r"\d+", node.get("bounds"))))


def tap_node(node):
    x1, y1, x2, y2 = rect(node)
    command("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def tap(label=None, desc=None):
    tap_node(wait(label, desc))


def capture(name):
    (output / f"{name}.png").write_bytes(command("exec-out", "screencap", "-p", binary=True))


def action(label):
    for _ in range(20):
        nodes = snapshot()
        target = find(label, nodes=nodes)
        if target is not None:
            tap_node(target)
            return
        buttons = [n for n in nodes if n.get("class") == "android.widget.Button"]
        assert buttons, "No document action row"
        _, y1, _, y2 = rect(max(buttons, key=lambda n: rect(n)[3]))
        command("shell", "input", "swipe", "940", str((y1+y2)//2), "170", str((y1+y2)//2), "350")
    raise AssertionError(f"Document action missing: {label}")


def documents():
    data = command("exec-out", "run-as", "com.kingscan.app", "tar", "-C", "files/kingscan/documents", "-cf", "-", ".", binary=True)
    result = []
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        for member in archive.getmembers():
            if member.name.endswith("/meta.json"):
                result.append(json.load(archive.extractfile(member)))
    return result


def bytes_in_app(path):
    return command("exec-out", "run-as", "com.kingscan.app", "cat", path, binary=True)


def save_import(multiple):
    tap("全图"); tap("下一步"); wait("滤镜")
    tap("原图")
    tap("保存并编辑下一张" if multiple else "完成")
    if multiple:
        tap("全图"); tap("下一步"); wait("滤镜"); tap("原图"); tap("完成")
    wait("重命名")


# A deterministic fixture, distinct from the user's documents, with untouched originals.
metadata = []
buffer = io.BytesIO()
with tarfile.open(fileobj=buffer, mode="w") as archive:
    for index, name in enumerate(["retouch-input.jpg", "ocr-input.jpg"]):
        content = (fixtures / name).read_bytes()
        filename = f"ui-{index}.jpg"
        for target in [filename, f"original-{filename}"]:
            info = tarfile.TarInfo(f"documents/localuiacceptance/pages/{target}")
            info.size = len(content)
            archive.addfile(info, io.BytesIO(content))
        metadata.append(dict(id=f"ui-{index}", fileName=filename, width=1000 if index == 0 else 1200,
                             height=700 if index == 0 else 1600, filter="Original",
                             originalFileName=f"original-{filename}", edit=dict(filter="Original")))
    encoded = json.dumps(dict(id="localuiacceptance", title="UI验收文档", createdAt=1795400000000,
                              updatedAt=1795400000000, pages=metadata), ensure_ascii=False).encode()
    info = tarfile.TarInfo("documents/localuiacceptance/meta.json")
    info.size = len(encoded)
    archive.addfile(info, io.BytesIO(encoded))
command("shell", "am", "force-stop", "com.kingscan.app")
command("shell", "run-as", "com.kingscan.app", "mkdir", "-p", "files/kingscan/documents")
command("shell", "run-as", "com.kingscan.app", "rm", "-rf", "files/kingscan/documents/local-ui-acceptance")
command("exec-in", "run-as", "com.kingscan.app", "tar", "-C", "files/kingscan", "-xf", "-", data=buffer.getvalue())
before = {d["id"] for d in documents()}
command("shell", "am", "start", "-n", "com.kingscan.verification.sender/.ShareActivity")
wait("裁切")
capture("android-cold-send-crop")
save_import(False)
created = [d for d in documents() if d["id"] not in before]
assert len(created) == 1 and len(created[0]["pages"]) == 1
page = created[0]["pages"][0]
assert bytes_in_app(f'files/kingscan/documents/{created[0]["id"]}/pages/{page["originalFileName"]}') == (fixtures / "retouch-input.jpg").read_bytes()
before.add(created[0]["id"])
command("shell", "am", "start", "--activity-multiple-task", "-n", "com.kingscan.verification.sender/.ShareActivity", "--ez", "multiple", "true")
wait("裁切")
save_import(True)
created = [d for d in documents() if d["id"] not in before]
assert len(created) == 1 and len(created[0]["pages"]) == 2
for page, fixture in zip(created[0]["pages"], ["retouch-input.jpg", "ocr-input.jpg"]):
    assert bytes_in_app(f'files/kingscan/documents/{created[0]["id"]}/pages/{page["originalFileName"]}') == (fixtures / fixture).read_bytes()
capture("android-hot-multiple-detail")

command("shell", "am", "force-stop", "com.kingscan.app")
command("shell", "am", "start", "-n", "com.kingscan.app/.MainActivity")
wait(desc="更多")
tap_node(next(n for n in snapshot() if n.get("class") == "android.widget.EditText"))
command("shell", "input", "text", "UI")
command("shell", "input", "keyevent", "KEYCODE_BACK")
tap("UI验收文档")
action("擦除与修复")
preview = wait(desc="修复预览")
x1, y1, x2, y2 = rect(preview)
# Select the black patch in the known test input.
nodes = snapshot()
sliders = [n for n in nodes if n.get("class") == "android.widget.SeekBar"]
assert sliders
sx1, sy1, sx2, sy2 = rect(sliders[0])
command("shell", "input", "tap", str(int(sx1 + (sx2-sx1)*0.8)), str((sy1+sy2)//2))
command("shell", "input", "swipe", str(int(x1+(x2-x1)*0.75)), str(int(y1+(y2-y1)*0.745)),
        str(int(x1+(x2-x1)*0.80)), str(int(y1+(y2-y1)*0.745)), "450")
capture("android-retouch-selection")
tap("擦除选区"); tap("去彩笔"); tap("重置修复"); tap("撤销")
snapshot(); snapshot()  # Observe completed preview recomposition before recording the screenshot.
capture("android-retouch-undo")
tap("保存"); wait("重命名")
stored = next(d for d in documents() if d["id"] == "localuiacceptance")
page = stored["pages"][0]
assert len(page["edit"]["retouches"]) == 2
assert bytes_in_app(f'files/kingscan/documents/localuiacceptance/pages/{page["originalFileName"]}') == (fixtures / "retouch-input.jpg").read_bytes()
(output / "ui-retouch-result.jpg").write_bytes(bytes_in_app(f'files/kingscan/documents/localuiacceptance/pages/{page["fileName"]}'))
action("系统打印")
deadline = time.monotonic() + 20
while time.monotonic() < deadline:
    if any(n.get("package") == "com.android.printspooler" for n in snapshot()):
        break
else:
    raise AssertionError("Native print panel did not open")
capture("android-system-print")
command("shell", "input", "keyevent", "KEYCODE_BACK")
(output / "result.txt").write_text("PASS cold external SEND with URI grant\nPASS hot external SEND_MULTIPLE order and originals\n"
    "PASS actual erase gesture and apply\nPASS reset and undo preserve two edits\nPASS UI save retains original\nPASS system print panel\n")
print((output / "result.txt").read_text())

"""Verify QR generation, gallery publication and capture-mode UI on an owned emulator."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("--serial", default="emulator-5554")
parser.add_argument("--output", default="/tmp/kingscan-android-tools-ui")
args = parser.parse_args()
output = Path(args.output)
output.mkdir(parents=True, exist_ok=True)
adb = [str(Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk")) / "platform-tools/adb"),
       "-s", args.serial]


def command(*parts, binary=False):
    result = subprocess.run(adb + list(parts), capture_output=True, check=True)
    return result.stdout if binary else result.stdout.decode(errors="replace")


def snapshot():
    command("shell", "uiautomator", "dump", "/sdcard/kingscan-tools-ui.xml")
    raw = command("exec-out", "cat", "/sdcard/kingscan-tools-ui.xml")
    (output / "last-ui.xml").write_text(raw)
    return list(ET.fromstring(raw).iter("node"))


def find(label=None, desc=None):
    return next((n for n in snapshot() if (label is not None and n.get("text") == label) or
                 (desc is not None and n.get("content-desc") == desc)), None)


def wait(label=None, desc=None):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        node = find(label, desc)
        if node is not None:
            return node
    raise AssertionError(f"Missing {label or desc}")


def tap_node(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    command("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))


def tap(label=None, desc=None):
    tap_node(wait(label, desc))


def capture(name):
    (output / f"{name}.png").write_bytes(command("exec-out", "screencap", "-p", binary=True))

def wait_prefix(prefix):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        if any(n.get("text", "").startswith(prefix) for n in snapshot()):
            return
    raise AssertionError(f"Missing guidance {prefix}")


command("shell", "am", "force-stop", "com.kingscan.app")
command("shell", "pm", "grant", "com.kingscan.app", "android.permission.CAMERA")
command("shell", "am", "start", "-n", "com.kingscan.app/.MainActivity")
tap(desc="更多"); tap("二维码与条码")
field = next(n for n in snapshot() if n.get("class") == "android.widget.EditText")
tap_node(field)
command("shell", "input", "text", "KingOfScan_OFFLINE_2026")
command("shell", "input", "keyevent", "KEYCODE_BACK")
tap("生成二维码")
wait(desc="生成的二维码")
command("shell", "input", "swipe", "540", "1900", "540", "800", "400")
wait("保存到相册")
capture("qr-generated")
before = set(command("shell", "ls", "-1", "/sdcard/Pictures/KingOfScan").splitlines())
tap("保存到相册")
wait("已保存 1 张到相册")
after = set(command("shell", "ls", "-1", "/sdcard/Pictures/KingOfScan").splitlines())
created = after - before
assert len(created) == 1
name = created.pop()
gallery = command("exec-out", "cat", f"/sdcard/Pictures/KingOfScan/{name}", binary=True)
assert gallery == command("exec-out", "run-as", "com.kingscan.app", "cat", "files/kingscan/exports/qrcode.jpg", binary=True)
(output / "gallery-qr.jpg").write_bytes(gallery)
tap("分享二维码")
deadline = time.monotonic() + 20
while time.monotonic() < deadline:
    if any(n.get("package") == "com.android.intentresolver" for n in snapshot()):
        break
else:
    raise AssertionError("Native image share sheet did not open")
capture("qr-share-sheet")
command("shell", "input", "keyevent", "KEYCODE_BACK")
command("shell", "input", "keyevent", "KEYCODE_BACK")
tap(desc="扫描")
wait("文档")
tap("大幅面")
wait("完成并拼接")
wait("保持方向一致，相邻画面保留约一半重叠，手动拍摄")
wait("连续拍摄")
capture("large-format-entry")
tap("书籍"); wait_prefix("横放摊开的书 · ")
capture("book-entry")
tap("证件")
wait_prefix("拍摄证件正面 · ")
capture("id-entry")
tap("文档")
tap(desc="关闭")
(output / "result.txt").write_text(
    "PASS actual QR text entry and generation preview\n"
    "PASS QR gallery UI publishes byte-identical JPEG\n"
    "PASS actual QR native share sheet\n"
    "PASS large-format continuous capture entry and overlap guidance\n"
    "PASS book and ID capture entries\n")
print((output / "result.txt").read_text())

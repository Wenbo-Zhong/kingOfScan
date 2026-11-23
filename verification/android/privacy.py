"""Verify device credentials and draft privacy on an owned emulator with test PIN 2468.

Set the PIN before running; disable app protection and clear the PIN after the run.
The script never changes the emulator's system credential.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("--serial", default="emulator-5554")
parser.add_argument("--output", default="/tmp/kingscan-android-privacy")
args = parser.parse_args()
output = Path(args.output)
output.mkdir(parents=True, exist_ok=True)
adb = [str(Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk")) / "platform-tools/adb"),
       "-s", args.serial]


def command(*parts, binary=False):
    result = subprocess.run(adb + list(parts), capture_output=True, check=True)
    return result.stdout if binary else result.stdout.decode(errors="replace")


def snapshot(name=None):
    command("shell", "uiautomator", "dump", "/sdcard/kingscan-privacy.xml")
    raw = command("exec-out", "cat", "/sdcard/kingscan-privacy.xml")
    (output / f"{name or 'last-ui'}.xml").write_text(raw)
    return list(ET.fromstring(raw).iter("node"))


def find(text=None, desc=None, nodes=None):
    for node in snapshot() if nodes is None else nodes:
        if ((text is not None and node.get("text") == text) or
                (desc is not None and node.get("content-desc") == desc)):
            return node
    return None


def wait(text=None, desc=None):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        node = find(text, desc)
        if node is not None:
            return node
    raise AssertionError(f"Missing {text or desc}")


def rect(node):
    return tuple(map(int, re.findall(r"\d+", node.get("bounds"))))


def tap_node(node):
    x1, y1, x2, y2 = rect(node)
    command("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))


def tap(text=None, desc=None):
    tap_node(wait(text, desc))


def capture(name):
    (output / f"{name}.png").write_bytes(command("exec-out", "screencap", "-p", binary=True))

def enabled(label):
    nodes = snapshot()
    x1, y1, x2, y2 = rect(find(label, nodes=nodes))
    x, y = (x1+x2)//2, (y1+y2)//2
    containers = [n for n in nodes if n.get("class") == "android.view.View" and
                  rect(n)[0] <= x <= rect(n)[2] and rect(n)[1] <= y <= rect(n)[3]]
    node = min(containers, key=lambda n: (rect(n)[2]-rect(n)[0]) * (rect(n)[3]-rect(n)[1]))
    return node.get("enabled") == "true"


def background():
    command("shell", "input", "keyevent", "KEYCODE_HOME")
    # Wait until the launcher is actually visible; immediate am start can cancel ON_STOP.
    nodes = snapshot("home")
    assert any("launcher" in n.get("package", "") for n in nodes), "Home transition did not finish"
    command("shell", "am", "start", "-n", "com.kingscan.app/.MainActivity")


def setting_switch():
    for _ in range(10):
        nodes = snapshot()
        label = find("设备锁保护", nodes=nodes)
        if label is not None:
            _, y1, _, y2 = rect(label)
            candidates = [n for n in nodes if n.get("checkable") == "true" and
                          rect(n)[1] <= (y1+y2)//2 <= rect(n)[3]]
            assert len(candidates) == 1, "Device lock switch not uniquely exposed"
            return candidates[0]
        command("shell", "input", "swipe", "530", "1850", "530", "620", "450")
    raise AssertionError("Device lock setting missing")


def pin(value):
    wait(desc="PIN area")
    field = next(n for n in snapshot() if n.get("class") == "android.widget.EditText")
    tap_node(field)
    command("shell", "input", "text", value)
    command("shell", "input", "keyevent", "KEYCODE_ENTER")


def app_settings():
    return json.loads(command("exec-out", "run-as", "com.kingscan.app", "cat", "files/kingscan/library.json")).get("settings", {})


def action(label):
    for _ in range(20):
        nodes = snapshot()
        target = find(label, nodes=nodes)
        if target is not None:
            tap_node(target)
            return
        buttons = [n for n in nodes if n.get("class") == "android.widget.Button"]
        assert buttons
        _, y1, _, y2 = rect(max(buttons, key=lambda n: rect(n)[3]))
        command("shell", "input", "swipe", "940", str((y1+y2)//2), "170", str((y1+y2)//2), "350")
    raise AssertionError(f"Missing action {label}")


command("shell", "am", "force-stop", "com.kingscan.app")
command("shell", "am", "start", "-n", "com.kingscan.app/.MainActivity")
if find("扫描件已锁定") is not None:
    tap("解锁"); pin("2468")
tap(desc="更多"); tap("设置")
if setting_switch().get("checked") == "true":
    tap_node(setting_switch()); pin("2468")
assert setting_switch().get("checked") == "false"
tap_node(setting_switch())
pin("2468")
assert setting_switch().get("checked") == "true"
assert app_settings()["deviceLock"]
windows = command("shell", "dumpsys", "window", "windows")
(output / "enabled-window.txt").write_text(windows)
own_window = next(block for block in windows.split("Window #") if
                  "com.kingscan.app/com.kingscan.app.MainActivity" in block and re.search(r"\bSECURE\b", block))
assert re.search(r"\bSECURE\b", own_window)
command("shell", "input", "keyevent", "KEYCODE_BACK")
wait(desc="更多")
tap_node(next(n for n in snapshot() if n.get("class") == "android.widget.EditText"))
command("shell", "input", "text", "UI")
command("shell", "input", "keyevent", "KEYCODE_BACK")
tap("UI验收文档")
action("擦除与修复")
tap("去彩笔")
wait("撤销")
assert enabled("撤销")
background()
wait("扫描件已锁定")
locked = snapshot("locked-ui")
assert find("图像修复", nodes=locked) is None
assert find("保存", nodes=locked) is None
assert find(desc="修复预览", nodes=locked) is None
# Taps on empty overlay space must not reach the retained editor.
command("shell", "input", "tap", "810", "1600")
assert find("扫描件已锁定") is not None
tap("解锁")
pin("1357")
wait("Wrong PIN")
capture("wrong-pin")
command("shell", "input", "keyevent", "KEYCODE_BACK")
wait("扫描件已锁定")
assert find("图像修复") is None
tap("解锁")
wait(desc="PIN area")
command("shell", "input", "keyevent", "KEYCODE_BACK")
wait("扫描件已锁定")
tap("解锁")
pin("2468")
wait("图像修复")
assert enabled("撤销"), "Draft history lost on lock"
tap("撤销")
tap("重做")
assert enabled("保存")
tap("保存")
wait("重命名")
stored = json.loads(command("exec-out", "run-as", "com.kingscan.app", "cat",
                            "files/kingscan/documents/localuiacceptance/meta.json"))
assert len(stored["pages"][0]["edit"]["retouches"]) == 3
command("shell", "input", "keyevent", "KEYCODE_BACK")
tap(desc="更多"); tap("设置")
tap_node(setting_switch())
pin("2468")
assert setting_switch().get("checked") == "false"
assert not app_settings().get("deviceLock", False)
windows = command("shell", "dumpsys", "window", "windows")
(output / "disabled-window.txt").write_text(windows)
own_windows = [block for block in windows.split("Window #") if
               "com.kingscan.app/com.kingscan.app.MainActivity" in block]
assert own_windows and all(not re.search(r"\bSECURE\b", block) for block in own_windows)
background()
assert find("扫描件已锁定") is None
wait("扫描设置")
capture("lock-disabled")
(output / "result.txt").write_text(
    "PASS enable after real device PIN authentication\n"
    "PASS FLAG_SECURE enabled and removed\n"
    "PASS background lock excludes editor accessibility and blocks taps\n"
    "PASS incorrect PIN and cancellation remain locked\n"
    "PASS correct PIN restores unsaved history and saves third edit\n"
    "PASS authenticated disable persists and background return remains unlocked\n")
print((output / "result.txt").read_text())

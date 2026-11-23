"""Exercise production model buttons and stored results on isolated AI fixture documents."""
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
parser.add_argument("--output", type=Path, default=Path("/tmp/kingscan-ai-android-ui"))
parser.add_argument("--models", nargs="+", choices=["docres", "handwriting", "fingers", "watermark", "esdnet"])
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
adb = [str(sdk / "platform-tools/adb"), "-s", args.serial]
lines = []


def run(*parts, binary=False):
    data = subprocess.run(adb + list(parts), check=True, capture_output=True, timeout=60).stdout
    return data if binary else data.decode(errors="replace")


def nodes():
    try:
        result = subprocess.run(adb + ["shell", "uiautomator", "dump", "/sdcard/kingscan-ai-ui.xml"],
                                check=True, capture_output=True, timeout=30)
    except subprocess.TimeoutExpired as failure:
        (args.output / "last-dump-error.txt").write_text(str(failure))
        return []  # wait() has its own deadline; never consume a stale hierarchy.
    dumped = (result.stdout + result.stderr).decode(errors="replace")
    if "dumped to:" not in dumped:
        (args.output / "last-dump-error.txt").write_text(dumped)
        return []  # No fresh tree yet, e.g. during cold-start layout; wait() remains bounded.
    raw = run("exec-out", "cat", "/sdcard/kingscan-ai-ui.xml")
    (args.output / "last-ui.xml").write_text(raw)
    root = ET.fromstring(raw)
    flat = []
    def visit(item, enabled=True):
        enabled = enabled and item.get("enabled", "true") == "true"
        item.set("effective-enabled", str(enabled).lower())
        if item.tag == "node":
            flat.append(item)
        for child in item:
            visit(child, enabled)
    visit(root)
    return flat


def find(label, tree, enabled=None):
    return next((n for n in tree if label in [n.get("text"), n.get("content-desc")]
                 and (enabled is None or (n.get("effective-enabled") == "true") == enabled)), None)


def wait(label, enabled=None, timeout=600):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        tree = nodes()
        if label == "保存" and any(n.get("text", "").startswith("正在处理") for n in tree):
            continue
        item = find(label, tree, enabled)
        if item is not None:
            return item
    raise AssertionError(f"Missing UI: {label}, enabled={enabled}")


def bounds(item):
    return list(map(int, re.findall(r"\d+", item.get("bounds"))))


def tap_item(item):
    x1, y1, x2, y2 = bounds(item)
    run("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))


def tap(label, enabled=True):
    tap_item(wait(label, enabled))


def capture(name):
    (args.output / f"{name}.png").write_bytes(run("exec-out", "screencap", "-p", binary=True))


def action(label, family):
    for _ in range(8):
        tree = nodes()
        if not tree:
            continue
        item = find(label, tree, True)
        if item is not None:
            tap_item(item)
            return
        items = [n for n in tree if n.get("text") in family]
        assert items, f"No action row for {label}"
        x1, y1, x2, y2 = bounds(items[0])
        width = max(bounds(n)[2] for n in tree)
        run("shell", "input", "swipe", str(width-80), str((y1+y2)//2),
            "80", str((y1+y2)//2), "350")
    raise AssertionError(f"Offscreen action missing: {label}")


def meta(name):
    return json.loads(run("exec-out", "run-as", "com.kingscan.app", "cat",
                          f"files/kingscan/documents/localaiverification{name}/meta.json"))


def page_bytes(name, filename):
    return run("exec-out", "run-as", "com.kingscan.app", "cat",
               f"files/kingscan/documents/localaiverification{name}/pages/{filename}", binary=True)


def open_editor(name):
    run("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    run("shell", "wm", "dismiss-keyguard")
    started = run("shell", "am", "start", "-W", "-n", "com.kingscan.app/.MainActivity")
    (args.output / "last-start.txt").write_text(started)
    for _ in range(4):
        tree = nodes()
        if find("更多", tree) is not None:
            break
        if find("图像修复", tree) is not None or find("重命名", tree) is not None:
            run("shell", "input", "keyevent", "KEYCODE_BACK")
        elif not tree:
            continue
        else:
            raise AssertionError("Unexpected screen while returning to library")
    wait("更多", timeout=60)
    for _ in range(10):
        tree = nodes()
        if not tree:
            continue
        item = find(f"AI-{name}", tree, True)
        if item is not None:
            x1, y1, x2, y2 = bounds(item)
            if y2-y1 >= 45:
                tap_item(item)
                break
        scrolls = [n for n in tree if n.get("scrollable") == "true"
                   and bounds(n)[3]-bounds(n)[1] > 500]
        assert scrolls, "No document grid available"
        x1, y1, x2, y2 = bounds(max(scrolls, key=lambda n: bounds(n)[3]-bounds(n)[1]))
        run("shell", "input", "swipe", str(x1+(x2-x1)//3), str(y1+(y2-y1)*3//4),
            str(x1+(x2-x1)//3), str(y1+(y2-y1)//4), "500")
    else:
        raise AssertionError(f"Document card missing: AI-{name}")
    action("擦除与修复", ["页面管理", "拼版与拼接", "识别文字", "批注签名", "重新编辑"])
    wait("保存", True)


models = [("docres", "智能高清", "SmartHd"), ("handwriting", "去黑色手写", "Handwriting"),
          ("fingers", "去手指", "Fingers"), ("watermark", "去水印", "Watermark"),
          ("esdnet", "去摩尔纹", "Demoire")]
for name, label, kind in models:
    if args.models and name not in args.models:
        continue
    print(f"START {name}", flush=True)
    original = meta(name)["pages"][0]
    original_bytes = page_bytes(name, original["originalFileName"])
    source_bytes = page_bytes(name, original["fileName"])
    open_editor(name)
    if name == "docres":
        tap(label)
        tap("取消本次处理")
        wait("保存", True)
        capture("cancelled")
        tap("保存")
        wait("重命名", timeout=30)
        assert meta(name)["pages"][0]["edit"].get("retouches", []) == []
        lines.append("PASS actual in-flight cancellation saves no model edit")
        print(lines[-1], flush=True)
        open_editor(name)
    action(label, [entry[1] for entry in models])
    capture(f"{name}-processing")
    wait("保存", True)
    capture(f"{name}-result")
    tap("对比原图")
    wait("查看结果", timeout=30)
    capture(f"{name}-original")
    tap("查看结果")
    if name == "docres":
        tap("撤销")
        wait("保存", True)
        capture("undo")
        tap("重做")
        wait("保存", True)
        capture("redo")
        lines.append("PASS model undo and redo produce ready preview")
    tap("保存")
    wait("重命名", timeout=30)
    page = meta(name)["pages"][0]
    edit = page["edit"]["retouches"]
    assert len(edit) == 1 and edit[0]["kind"] == kind, edit
    assert edit[0]["modelVersion"] == "kingscan-document-ai-20261123-v1"
    assert edit[0]["seed"] == 20261123 and edit[0]["orientation"] == 0 and edit[0]["strength"] == .5
    assert page_bytes(name, page["originalFileName"]) == original_bytes
    result = page_bytes(name, page["fileName"])
    assert result != source_bytes
    assert (page["width"], page["height"]) == (original["width"], original["height"])
    (args.output / f"{name}-saved.jpg").write_bytes(result)
    (args.output / f"{name}-meta.json").write_text(json.dumps(meta(name), ensure_ascii=False, indent=2))
    open_editor(name)
    wait("保存", True)
    capture(f"{name}-reopened")
    # Opening must not append another edit or modify the saved original.
    assert meta(name)["pages"][0]["edit"]["retouches"] == edit
    assert page_bytes(name, page["originalFileName"]) == original_bytes
    lines.append(f"PASS {name} button, compare, save, explicit parameters, original bytes and reopened preview")
    print(lines[-1], flush=True)
    (args.output / "progress.txt").write_text("\n".join(lines))
(args.output / "result.txt").write_text("\n".join(lines))
print("\n".join(lines))

#!/usr/bin/env python3
"""Reproduce (or fail to reproduce) the blank first-boot difficulty dialog.

The dialog renders its frame, banner and prompt but leaves the options list an
empty box, and it does not happen every time - which is the whole reason this
exists. One run proves nothing either way; the point is a rate over many cold
boots, with the renderer state that went with each one.

Each iteration wipes the save so the dialog appears at all (the game recreates
it, so it is one dialog per wipe), cold-boots the game, then polls the screen
until the dialog shows up. When it does, the options box is classified by how
many bright pixels it holds: option text is bright on a near-black panel, so
"blank" and "populated" are far apart rather than a judgement call.

usage: first_boot_menu_probe.py [runs] [--cold-shaders] [--keep-shots]
"""
import json
import re
import statistics
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image

PKG = "com.nakas.skate3"
FILES = f"/sdcard/Android/data/{PKG}/files"
LOG = f"{FILES}/skate3.log"

# Regions in the phone's own 2340x1080 pixels, read off a captured dialog.
# Scaled by the actual screenshot size so a different device still lands on
# the same part of the picture.
REF_W, REF_H = 2340.0, 1080.0
BANNER = (796, 252, 1574, 433)   # the blue "skate3" masthead: says the dialog is up
OPTIONS = (1090, 570, 1575, 690)  # the box the difficulty options belong in
# The masthead is a bright photo, and so is any gameplay frame - on its own it
# called the attract reel a dialog. These two bands are menu backdrop behind
# the dialog and open world during play, which tells the two apart: measured
# 36/17 on real dialogs against 79-111/33-72 on gameplay.
SURROUND_TOP = (700, 40, 1700, 200)
SURROUND_BOTTOM = (700, 860, 1700, 1050)

# A lit pixel. The panel behind the options is near-black, so anything this
# bright is drawn content rather than the panel itself.
BRIGHT = 110
# Above this share of lit pixels the box has something in it. The measured
# gap is wide - an empty box sits near zero - so the exact threshold is not
# load-bearing.
POPULATED = 0.012


def sh(args, **kw):
    return subprocess.run(args, capture_output=True, text=True, timeout=120, **kw)


def adb(*args, **kw):
    return sh(["adb", *args], **kw)


def screencap(path: Path) -> bool:
    raw = subprocess.run(["adb", "exec-out", "screencap", "-p"],
                         capture_output=True, timeout=120).stdout
    if not raw or len(raw) < 1024:
        return False
    path.write_bytes(raw)
    return True


def crop_stats(image: Image.Image, box) -> float:
    """Share of pixels in `box` bright enough to be drawn content."""
    sx, sy = image.width / REF_W, image.height / REF_H
    left, top, right, bottom = box
    region = image.convert("L").crop(
        (int(left * sx), int(top * sy), int(right * sx), int(bottom * sy)))
    pixels = np.asarray(region, dtype=np.uint8)
    if pixels.size == 0:
        return 0.0
    return float((pixels >= BRIGHT).mean())


def mean_luma(image: Image.Image, box) -> float:
    sx, sy = image.width / REF_W, image.height / REF_H
    left, top, right, bottom = box
    region = image.convert("L").crop(
        (int(left * sx), int(top * sy), int(right * sx), int(bottom * sy)))
    pixels = np.asarray(region, dtype=np.uint8)
    return float(pixels.mean()) if pixels.size else 255.0


def dialog_is_up(image: Image.Image) -> bool:
    # Masthead lit AND the screen around it still menu-dark. The second half
    # is what keeps the attract reel out: it is every bit as bright as the
    # masthead and was being counted as a dialog without it.
    return (crop_stats(image, BANNER) > 0.30
            and mean_luma(image, SURROUND_TOP) < 55.0
            and mean_luma(image, SURROUND_BOTTOM) < 25.0)


def log_state() -> dict:
    """The renderer's own account of the frame, for correlating with what was drawn."""
    out = adb("shell", f"grep -oE 'native-scene: alive.*' {LOG} | tail -1").stdout
    alive = {}
    for key in ("items", "draws_2d", "presence", "loading_native", "published_items"):
        m = re.search(rf"{key}=(\d+)", out)
        if m:
            alive[key] = int(m.group(1))
    sup = adb("shell",
              f"grep -oE '\\[cp-draw\\] draws=[0-9]+ .*suppressed=[0-9]+ \\([0-9]+%\\)' {LOG} | tail -1").stdout
    m = re.search(r"suppressed=(\d+) \((\d+)%\)", sup)
    if m:
        alive["suppressed_pct"] = int(m.group(2))
    # The hypothesis in one number: draws skipped because their pipeline was
    # still compiling. A warm cache compiles nothing and reports zero here.
    placeholder = adb("shell",
                      f"grep -c 'async placeholder draw' {LOG}").stdout.strip()
    alive["placeholder_frames"] = int(placeholder) if placeholder.isdigit() else -1
    cold = adb("shell",
               f"grep -c 'cold shader cache' {LOG}").stdout.strip()
    alive["cold_compiles"] = int(cold) if cold.isdigit() else -1
    return alive


def find_button(label: str):
    """Centre of a button by its exact label, or None."""
    adb("shell", "uiautomator", "dump", "/sdcard/probe.xml")
    xml = adb("shell", "cat", "/sdcard/probe.xml").stdout
    for node in re.finditer(r"<node[^>]*>", xml):
        seg = node.group(0)
        text = re.search(r'text="([^"]*)"', seg)
        bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', seg)
        if text and bounds and text.group(1).strip().upper() == label:
            x1, y1, x2, y2 = map(int, bounds.groups())
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def tap_play(timeout_s: float = 30.0) -> bool:
    """Get the launcher to PLAY, waiting for it to draw and clearing dialogs.

    A fixed sleep was not enough: the launcher takes a variable time to come
    up after a force-stop, and a crash dialog left over from the previous
    iteration sits on top of it. Both showed up as "launcher-not-found" and
    cost a whole run - including its shader-cache wipe, which is the scarce
    part of the setup.
    """
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        spot = find_button("PLAY")
        if spot:
            adb("shell", "input", "tap", str(spot[0]), str(spot[1]))
            return True
        dismiss = find_button("OK")
        if dismiss:
            adb("shell", "input", "tap", str(dismiss[0]), str(dismiss[1]))
        time.sleep(2)
    return False


def one_run(index: int, shots: Path, keep: bool, cold_shaders: bool = False) -> dict:
    # A save suppresses the dialog entirely, so it has to go first. Only the
    # profile directories are removed - the 6 GB of game data is untouched.
    adb("shell", "am", "force-stop", PKG)
    time.sleep(2)
    if cold_shaders:
        # The reason to have this switch at all: with async shader compilation
        # on, a draw whose pipeline is still building is SKIPPED outright
        # (vulkan command_processor.cpp, the pipeline_is_placeholder branch).
        # That is harmless for a frame the game will draw again next tick and
        # permanent for anything rendered once and kept. A warm cache compiles
        # nothing and so can never show it - which is exactly why this stopped
        # reproducing by hand after the first run.
        adb("shell", f"rm -rf {FILES}/user/cache/nrhi_shaders {FILES}/user/cache/shaders")
    adb("shell", f"ls -d {FILES}/user/*/ 2>/dev/null")
    for xuid in re.findall(r"user/([0-9A-Fa-f]{16})/",
                           adb("shell", f"ls -d {FILES}/user/*/ 2>/dev/null").stdout):
        adb("shell", f"rm -rf {FILES}/user/{xuid}")
    adb("shell", f"rm -f {LOG}")

    # Anything of ours that force-stop can reach is gone, but a system
    # activity we opened earlier - the document picker, most often - belongs
    # to another package and survives, sits on top, and swallows the launcher.
    # That read as "launcher-not-found" and burned a run, shader-cache wipe
    # and all.
    for _ in range(4):
        focus = adb("shell", "dumpsys window | grep -i mCurrentFocus").stdout
        if PKG in focus or "launcher" in focus.lower():
            break
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(1)

    adb("shell", "am", "start", "-n", f"{PKG}/.SetupActivity")
    time.sleep(3)
    if not tap_play():
        return {"run": index, "result": "launcher-not-found"}

    deadline = time.time() + 240
    shot = shots / f"run{index:02d}.png"
    while time.time() < deadline:
        time.sleep(1.0)
        if not screencap(shot):
            continue
        with Image.open(shot) as image:
            image.load()
            if not dialog_is_up(image):
                continue
            # Let it settle: the dialog animates in, and a frame caught
            # mid-fade would read as blank whatever the bug is doing.
            time.sleep(1.5)
            if not screencap(shot):
                continue
            with Image.open(shot) as settled:
                settled.load()
                if not dialog_is_up(settled):
                    continue
                filled = crop_stats(settled, OPTIONS)
                state = log_state()
                verdict = "populated" if filled >= POPULATED else "BLANK"
                if not keep:
                    keep_path = shots / f"run{index:02d}-{verdict}.png"
                    shot.replace(keep_path)
                return {"run": index, "result": verdict,
                        "options_fill": round(filled, 5), **state}
    return {"run": index, "result": "dialog-never-appeared"}


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    keep = "--keep-shots" in sys.argv
    cold = "--cold-shaders" in sys.argv
    runs = int(args[0]) if args else 5

    if "device" not in adb("devices").stdout:
        print("no authorized device")
        return 2

    shots = Path(__file__).resolve().parent.parent / "build" / "menu-probe"
    shots.mkdir(parents=True, exist_ok=True)

    results = []
    for i in range(1, runs + 1):
        row = one_run(i, shots, keep, cold)
        results.append(row)
        print(f"run {i}/{runs}: {row.get('result')} "
              f"fill={row.get('options_fill')} "
              f"loading_native={row.get('loading_native')} "
              f"suppressed={row.get('suppressed_pct')}%", flush=True)

    seen = [r for r in results if r["result"] in ("BLANK", "populated")]
    blank = [r for r in seen if r["result"] == "BLANK"]
    print("\n--- summary ---")
    print(f"shader cache     : {'COLD each run' if cold else 'warm'}")
    print(f"dialogs observed : {len(seen)}/{runs}")
    print(f"blank            : {len(blank)}")
    if seen:
        fills = [r["options_fill"] for r in seen]
        print(f"fill min/median/max: {min(fills):.5f} / "
              f"{statistics.median(fills):.5f} / {max(fills):.5f}")
    report = shots / "report.json"
    report.write_text(json.dumps(results, indent=2))
    print(f"report: {report}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

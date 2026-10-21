"""Fast emulator driver for Connections Wallpaper: uiautomator2 for the UI, adb for state.

Everything here is a plain function so a whole test run is one script, with no model in the loop.
Needs `pip install uiautomator2 pillow`, and an emulator with `adb root` (a userdebug image, no
Google Play) so the wallpaper files can be read.
"""
import os
import re
import shutil
import subprocess
import tempfile
import time
from collections import Counter

import uiautomator2 as u2



def _adb() -> str:
    if os.environ.get("ADB"):
        return os.environ["ADB"]
    for home in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"), r"C:\android-sdk"):
        for exe in ("adb.exe", "adb"):
            if home and os.path.isfile(os.path.join(home, "platform-tools", exe)):
                return os.path.join(home, "platform-tools", exe)
    return shutil.which("adb") or "adb"


ADB = _adb()
PKG = "icu.connections.wallpaper"
MAIN = f"{PKG}/com.anthonyla.paperize.presentation.MainActivity"
SHORTCUT = f"{PKG}/com.anthonyla.paperize.service.shortcut.WallpaperShortcutActivity"
SERIAL = os.environ.get("ANDROID_SERIAL", "emulator-5554")
# No console window per adb call on Windows; any other OS refuses creation flags.
NO_WINDOW = 0x08000000 if os.name == "nt" else 0
# The four solid test pictures, as RGB: far enough apart that JPEG noise never confuses two of them.
COLORS = {"blue": (26, 115, 232), "gold": (249, 171, 0), "green": (30, 142, 62), "red": (217, 48, 37)}

d = None


def sh(*args: str, check: bool = False) -> str:
    r = subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, creationflags=NO_WINDOW)
    out = r.stdout.decode("utf-8", "replace")
    if check and r.returncode:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr.decode()[:300]}")
    return out


def connect(serial: str = SERIAL):
    global d, SERIAL
    SERIAL = serial
    d = u2.connect(serial)
    d.settings["wait_timeout"] = 8
    d.settings["operation_delay"] = (0, 0)
    return d


def push_testwalls(folder: str) -> None:
    """Put the four test pictures in Pictures/<folder> on the device, so a test can tell which one is up by colour.

    Each is a 12-megapixel camera-sized photo of one colour with film grain, as heavy to decode as a
    real photo, so timings measured here are the ones a phone sees.
    """
    from PIL import Image
    sh("root")
    sh("wait-for-device")
    grain = Image.effect_noise((3000, 4000), 14)
    with tempfile.TemporaryDirectory() as tmp:
        for name, rgb in COLORS.items():
            path = os.path.join(tmp, f"test_{name}.jpg")
            channels = [grain.point(lambda v, c=c: max(0, min(255, c + v - 128))) for c in rgb]
            Image.merge("RGB", channels).save(path, quality=92)
            sh("push", path, f"/sdcard/Pictures/{folder}/test_{name}.jpg", check=True)


def nearest(rgb) -> str:
    best, dist = "other", 60 ** 2 * 3
    for name, ref in COLORS.items():
        dd = sum((a - b) ** 2 for a, b in zip(rgb, ref))
        if dd < dist:
            best, dist = name, dd
    return best


def screen_color(img=None) -> str:
    """The test colour most of a 9x15 grid of the screen shows (icons and clocks are outvoted)."""
    img = img or d.screenshot()
    w, h = img.size
    votes = Counter(nearest(img.getpixel((int(w * (i + .5) / 9), int(h * (j + .5) / 15)))) for i in range(9) for j in range(15))
    top = votes.most_common(1)[0]
    return top[0] if top[1] >= 25 else f"other{dict(votes)}"


def labels() -> list[str]:
    xml = d.dump_hierarchy(compressed=True)
    out = []
    for m in re.finditer(r'<node [^>]*>', xml):
        n = m.group(0)
        t = re.search(r' text="([^"]*)"', n).group(1) or re.search(r'content-desc="([^"]*)"', n).group(1)
        if t:
            out.append(t)
    return out


def tap(text: str, timeout: float = 8, contains: bool = False) -> None:
    """Click a label, finding it again if the screen redraws between the find and the click."""
    for attempt in range(3):
        sel = d(textContains=text) if contains else d(text=text)
        if not sel.wait(timeout=timeout):
            sel = d(descriptionContains=text) if contains else d(description=text)
            if not sel.wait(timeout=1):
                raise AssertionError(f"no {text!r} on screen; saw {labels()[:40]}")
        try:
            sel.click()
            return
        except Exception as e:  # noqa: BLE001 - uiautomator2 reports a stale node only as an RPC error message
            if "StaleObjectException" not in str(e) or attempt == 2:
                raise
            time.sleep(0.5)


def has(text: str, timeout: float = 0) -> bool:
    sel = d(textContains=text)
    if timeout:
        return sel.wait(timeout=timeout) or d(descriptionContains=text).exists
    return sel.exists or d(descriptionContains=text).exists


def wallpaper_ids() -> dict:
    """System and lock wallpaper ids from dumpsys; a change of id is a new wallpaper set."""
    out = sh("shell", "dumpsys", "wallpaper")
    ids = {}
    for section, key in (("System wallpaper state", "system"), ("Lock wallpaper state", "lock")):
        i = out.find(section)
        if i >= 0:
            m = re.search(r"\bid=(\d+)", out[i:i + 3000])
            ids[key] = int(m.group(1)) if m else None
    comp = re.search(r"mWallpaperComponent=(\S+)", out)
    ids["component"] = comp.group(1) if comp else None
    return ids


def wall_rgb(which: str = "lock") -> tuple:
    """The centre pixel of the wallpaper file itself (needs adb root): the lock one when set, else the shared one."""
    import io
    from PIL import Image
    name = "wallpaper_lock" if which == "lock" else "wallpaper"
    listing = sh("shell", "ls", "/data/system/users/0/")
    if name not in listing.split():
        name = "wallpaper"
    r = subprocess.run([ADB, "-s", SERIAL, "exec-out", "cat", f"/data/system/users/0/{name}"], capture_output=True, creationflags=NO_WINDOW)
    im = Image.open(io.BytesIO(r.stdout)).convert("RGB")
    return im.getpixel((im.width // 2, im.height // 2))


def wall_file_size(name: str) -> tuple:
    """Width and height of one of the system's wallpaper files (needs adb root): wallpaper_orig keeps the picture as set."""
    import io
    from PIL import Image
    r = subprocess.run([ADB, "-s", SERIAL, "exec-out", "cat", f"/data/system/users/0/{name}"], capture_output=True, creationflags=NO_WINDOW)
    return Image.open(io.BytesIO(r.stdout)).size


def wall_color(which: str = "lock") -> str:
    """The test colour of the wallpaper file itself."""
    return nearest(wall_rgb(which))


def is_gray(rgb, spread: int = 14) -> bool:
    return max(rgb) - min(rgb) <= spread


def element_rgb(description: str) -> tuple:
    """The centre pixel of the element with this content description, from a fresh screenshot."""
    b = d(description=description).info["bounds"]
    return d.screenshot().convert("RGB").getpixel(((b["left"] + b["right"]) // 2, (b["top"] + b["bottom"]) // 2))


def fresh_app(grant: bool = True) -> None:
    """Wipe the app's data and open it: the same as a first install."""
    sh("shell", "pm", "clear", PKG)
    if grant:
        sh("shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS")
    sh("shell", "am", "start", "-W", "-n", MAIN)


def onboard(mode: str = "Static") -> None:
    tap("Agree")
    tap(mode)
    tap("I'm In")
    for _ in range(4):  # notification and battery pages, whichever this build shows
        found = d(text="Skip").wait(timeout=2) or d(text="Library").exists
        if d(text="Skip").exists:
            d(text="Skip").click()
        elif d(text="Library").exists or not found:
            return


def home_color() -> str:
    d.press("home")
    time.sleep(1.2)
    return screen_color()


def lock_color() -> str:
    d.screen_off()
    time.sleep(1.0)
    d.screen_on()
    time.sleep(1.2)
    c = screen_color()
    sh("shell", "wm", "dismiss-keyguard")
    time.sleep(0.5)
    return c

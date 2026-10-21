r"""Connections Wallpaper end-to-end suite on the emulator. Each test prints PASS/FAIL with its time; a failure
saves shots/suite/FAIL-<test>.jpg and the labels on screen, then the run goes on.

  tools\emulator-suite\check.cmd                                (unit, lint, release build, device tests)
  adb -s emulator-5554 install -r app/build/outputs/apk/release/app-release.apk
  python -X utf8 tools/emulator-suite/suite.py [test ...]      (no names: every test, in order)
  python -X utf8 tools/emulator-suite/private.py [test ...]    (the same on a private emulator, when another session has 5554)
  python -X utf8 tools/emulator-suite/unlock_speed.py          (after lock_first_folder: screen-off to new lock picture)

Each run wipes the app's data first (the same as a fresh install) and takes about six minutes.
"""
import os
import re
import sys
import time
import traceback

import cw

os.chdir(os.path.dirname(os.path.abspath(__file__)))
os.makedirs("shots/suite", exist_ok=True)
T0 = time.time()
FOLDER = "CWTest"


def log(msg: str) -> None:
    print(f"  [{time.time() - T0:6.1f}s] {msg}", flush=True)


def wait_until(fn, timeout: float = 12, every: float = 0.4):
    end = time.time() + timeout
    last = None
    while time.time() < end:
        last = fn()
        if last:
            return last
        time.sleep(every)
    return last


def check(cond, msg: str) -> None:
    if not cond:
        raise AssertionError(msg)
    log(f"ok: {msg}")


def open_app() -> None:
    cw.sh("shell", "am", "start", "-n", cw.MAIN)
    cw.d(text="Library").wait(timeout=8)


def open_tab(name: str) -> None:
    """Open a tab at its top: a tab keeps its scroll between visits, and a label lower down can share a name."""
    open_app()
    cw.tap(name)
    time.sleep(0.5)
    page = cw.d(scrollable=True)
    if page.exists:
        page.scroll.toBeginning(max_swipes=10)


def pick_folder(folder: str = FOLDER) -> None:
    """Drive the system folder picker to Pictures/<folder> and allow it."""
    d = cw.d
    for _ in range(14):
        time.sleep(0.5)
        if d(description=f"Files in {folder}").exists or d(text=f"Files in {folder}").exists:
            break
        if d(text=folder).exists:
            d(text=folder).click()
            continue
        if d(text="Pictures").exists:
            d(text="Pictures").click()
            continue
        root = d(textMatches="(?i)sdk_gphone.*|Internal storage")
        if root.exists:
            root.click()
            continue
        if d(description="Show roots").exists:
            d(description="Show roots").click()
    d(textMatches="(?i)use this folder").click(timeout=5)
    d(textMatches="(?i)^allow$").click(timeout=5)
    check(wait_until(lambda: not d(packageName="com.google.android.documentsui").exists, 10), "picker closed")
    # The first album added while signed out brings the one-time free-account note; the suite stays signed out.
    if d(text="Not now").wait(timeout=3):
        d(text="Not now").click()


def ids() -> dict:
    return cw.wallpaper_ids()


def screen_off_on() -> None:
    cw.d.screen_off()
    time.sleep(1.5)
    cw.d.screen_on()
    time.sleep(1.0)


def unlock() -> None:
    cw.sh("shell", "wm", "dismiss-keyguard")
    time.sleep(0.6)


def watcher_running() -> bool:
    return "UnlockChangeService" in cw.sh("shell", "dumpsys", "activity", "services", cw.PKG)


def job_count() -> int:
    """The app's registered jobs: this Android lists each as `JOB <namespace>:<uid>/<id>: ... @<pkg>/...`."""
    out = cw.sh("shell", "dumpsys", "jobscheduler", cw.PKG)
    return len(re.findall(rf"^\s*JOB \S+: \S+ \S*{re.escape(cw.PKG)}/", out, re.M))


def switch_state(title: str) -> bool:
    """The checked state of the switch on the row titled <title> (same height as the title)."""
    t = cw.d(text=title)
    if not t.wait(timeout=5):
        raise AssertionError(f"no row {title!r}")
    b = t.info["bounds"]
    cy = (b["top"] + b["bottom"]) // 2
    for el in cw.d.xpath('//*[@checkable="true"]').all():
        lx, ly, rx, ry = el.bounds
        if ly <= cy <= ry:
            return el.attrib.get("checked") == "true"
    raise AssertionError(f"no switch beside {title!r}")


def scroll_to(text: str) -> None:
    if not cw.d(text=text).exists:
        cw.d(scrollable=True).scroll.to(text=text)


def tab_bottom_has(text: str, timeout: float = 4) -> bool:
    """Scroll the tab to its end, again until the timeout, so a row that just appeared below the fold counts."""
    def at_end() -> bool:
        cw.d(scrollable=True).scroll.toEnd(max_swipes=5)
        return cw.has(text)
    return wait_until(at_end, timeout)


def t_lock_first_folder():
    """The user's path: turn on Lock, open its album picker, add a folder from there."""
    cw.fresh_app()
    cw.onboard()
    before = ids()
    open_tab("Wallpaper")
    cw.tap("Lock")
    cw.tap("Lock album")
    check(cw.has("No albums yet", timeout=4), "empty picker says No albums yet")
    cw.d.screenshot("shots/suite/empty-picker.jpg")
    cw.tap("Add folder")
    pick_folder()
    check(cw.has(FOLDER, timeout=10), "album named after the folder shows on the Wallpaper tab")
    check(wait_until(lambda: ids()["lock"] not in (None, before["lock"]), 15), f"lock wallpaper set ({before} -> {ids()})")
    check(ids()["system"] == before["system"], "home untouched (home is off)")
    check(switch_state("Wallpaper changing"), "rotation turned on by the first album")
    check(switch_state("Change on unlock"), "Change on unlock is on")
    check(not switch_state("Change on a timer"), "timer is off")
    check(wait_until(watcher_running, 6), "unlock watcher running")
    cw.d.screenshot("shots/suite/wallpaper-tab-lock.jpg")


def t_unlock_changes():
    seen = []
    for i in range(3):
        before = ids()["lock"]
        time.sleep(1.2)  # the watcher ignores screen-offs closer than 1 s
        screen_off_on()
        changed = wait_until(lambda: ids()["lock"] != before, 8)
        seen.append(cw.wall_color("lock"))
        unlock()
        check(changed, f"unlock {i + 1}: lock wallpaper changed ({before} -> {ids()['lock']})")
    log(f"lock colours seen: {seen}")
    check(len(set(seen)) >= 2, "different pictures across unlocks")


def t_timer_toggle():
    open_tab("Wallpaper")
    jobs = job_count()
    cw.tap("Change on a timer")
    check(cw.has("Time interval", timeout=4), "timer on shows the interval")
    cw.d.screenshot("shots/suite/wallpaper-tab-timer.jpg")
    check(wait_until(lambda: job_count() > jobs, 6), f"a periodic job is scheduled ({jobs} -> {job_count()})")
    cw.tap("Change on a timer")
    check(wait_until(lambda: not cw.has("Time interval"), 4), "timer off hides the interval")
    check(wait_until(lambda: job_count() == jobs, 6), f"the periodic job is gone ({job_count()})")


def t_unlock_off():
    open_tab("Wallpaper")
    cw.tap("Change on unlock")
    check(wait_until(lambda: not watcher_running(), 6), "watcher stops when unlock is off")
    before = ids()["lock"]
    time.sleep(3.2)
    screen_off_on()
    time.sleep(2.5)
    unlock()
    check(ids()["lock"] == before, "no change on unlock while it is off")
    open_tab("Wallpaper")
    cw.tap("Change on unlock")
    check(wait_until(watcher_running, 6), "watcher back when unlock is on")


def t_pause():
    open_tab("Wallpaper")
    cw.tap("Wallpaper changing")
    check(wait_until(lambda: not watcher_running(), 6), "pausing stops the watcher")
    check(wait_until(lambda: not cw.has("Change on unlock"), 4), "paused, when it changes is hidden")
    cw.d.screenshot("shots/suite/wallpaper-tab-paused.jpg")
    open_tab("Wallpaper")
    cw.tap("Wallpaper changing")
    check(wait_until(watcher_running, 6), "resuming starts it again")
    check(switch_state("Change on unlock"), "resumed, Change on unlock is back and still on")


def t_library_folder_fills_both():
    """From Library, with nothing set up: the album is named after the folder and both screens use it."""
    cw.fresh_app()
    cw.onboard()
    before = ids()
    open_tab("Library")
    cw.tap("Add album")
    cw.tap("Add folder")
    pick_folder()
    check(cw.has(FOLDER, timeout=10), "album named after the folder, no name prompt")
    check(not cw.has("Enter the name"), "no name dialog")
    check(wait_until(lambda: ids()["system"] != before["system"], 15), f"wallpaper set ({before} -> {ids()})")
    open_tab("Wallpaper")
    labels = cw.labels()
    check(labels.count(FOLDER) >= 2, f"Lock and Home both use {FOLDER}: {labels[5:25]}")
    cw.d.screenshot("shots/suite/wallpaper-tab-both.jpg")
    check(tab_bottom_has("Visual effects"), "the Visual effects switch ends the tab")
    cw.tap("Visual effects")
    check(tab_bottom_has("Adjust visual effects"), "the switch shows Adjust visual effects")
    cw.tap("Adjust visual effects")
    check(cw.has("Lock screen preview", timeout=5) and cw.has("Home screen preview"), "both screens get a preview")
    cw.d.screenshot("shots/suite/effects-both.jpg")
    cw.d.press("back")
    cw.tap("Visual effects")
    check(wait_until(lambda: not cw.has("Adjust visual effects"), 3), "visual effects off again")


def t_same_folder_numbered():
    open_tab("Library")
    cw.tap("Add album")
    cw.tap("Add folder")
    pick_folder()
    check(cw.has(f"{FOLDER} 2", timeout=10), "a second album of the same folder is numbered")


def t_home_fill_empty_slot():
    """Home on with no album, Lock already set: a new album from Library fills only the empty slot."""
    open_tab("Wallpaper")
    # Turn home off and on again: its album is cleared, lock keeps its own.
    cw.tap("Home")
    time.sleep(0.6)
    cw.tap("Home")
    check(cw.has("No album selected", timeout=4), "home has no album")
    before = ids()
    open_tab("Library")
    cw.tap("Add album")
    cw.tap("Add folder")
    pick_folder()
    check(cw.has(f"{FOLDER} 3", timeout=10), "third album added")
    open_tab("Wallpaper")
    check(cw.has(f"{FOLDER} 3", timeout=4) and not cw.has("No album selected"), "the empty home slot took the new album")
    log(f"ids {before} -> {ids()}")


def app_is_dark() -> bool:
    """The app's own background, read in the 16dp side margin halfway down, where no row reaches."""
    img = cw.d.screenshot()
    r, g, b = img.getpixel((10, img.height // 2))[:3]
    return (r + g + b) / 3 < 80


def t_theme():
    """System default follows the phone both ways; Dark pins the app dark on a light phone."""
    cw.sh("shell", "cmd", "uimode", "night", "yes")
    open_tab("Wallpaper")
    check(wait_until(app_is_dark, 5), "phone dark: app dark")
    cw.sh("shell", "cmd", "uimode", "night", "no")
    check(wait_until(lambda: not app_is_dark(), 6), "phone light: app follows to light")
    cw.d.screenshot("shots/suite/wallpaper-tab-light.jpg")
    cw.tap("Settings")
    check(cw.has("System default", timeout=5), "Theme row says System default")
    cw.tap("Theme")
    check(cw.has("Light", timeout=4), "Theme dialog offers Light")
    cw.d.screenshot("shots/suite/theme-dialog.jpg")
    cw.tap("Dark")
    check(wait_until(app_is_dark, 5), "Dark pins the app dark while the phone is light")
    cw.d.screenshot("shots/suite/settings-dark.jpg")
    cw.tap("Theme")
    cw.tap("System default")
    check(wait_until(lambda: not app_is_dark(), 5), "System default follows the light phone again")
    cw.sh("shell", "cmd", "uimode", "night", "yes")
    check(wait_until(app_is_dark, 6), "and the dark phone again")
    cw.d.press("back")


def t_effects():
    """Visual effects: one switch on the tab, a page with a live preview, and the lock wallpaper follows the switch."""
    open_tab("Wallpaper")
    check(tab_bottom_has("Visual effects") and not cw.has("Adjust visual effects"),
          "at the end of the tab, no Adjust row while visual effects are off")
    cw.tap("Visual effects")
    check(tab_bottom_has("Adjust visual effects"), "the switch shows Adjust visual effects")
    cw.tap("Adjust visual effects")
    check(cw.has("Lock screen preview", timeout=5), "the page opens on a lock screen preview")
    check(not cw.has("%"), "no slider while every effect is off")
    check(wait_until(lambda: not cw.is_gray(cw.element_rgb("Lock screen preview")), 5),
          f"the preview starts in colour {cw.element_rgb('Lock screen preview')}")
    cw.tap("Grayscale")
    check(cw.has("%", timeout=3), "Grayscale on shows its slider")
    check(wait_until(lambda: cw.is_gray(cw.element_rgb("Lock screen preview")), 5),
          f"the preview turns gray {cw.element_rgb('Lock screen preview')}")
    cw.d.screenshot("shots/suite/effects-page.jpg")
    check(wait_until(lambda: cw.is_gray(cw.wall_rgb("lock")), 20), f"the lock wallpaper is redrawn gray {cw.wall_rgb('lock')}")
    cw.d.press("back")
    check(tab_bottom_has("Adjust visual effects"), "back on the Wallpaper tab, Adjust still there")
    check(cw.has("Grayscale"), "the Adjust row names the effect that is on")
    cw.d.screenshot("shots/suite/wallpaper-tab-effects.jpg")
    cw.tap("Visual effects")
    check(wait_until(lambda: tab_bottom_has("Visual effects", 1) and not cw.has("Adjust visual effects"), 4),
          "switching visual effects off hides Adjust")
    check(wait_until(lambda: not cw.is_gray(cw.wall_rgb("lock")), 20), f"and the lock wallpaper has its colour back {cw.wall_rgb('lock')}")


def t_swipe_back():
    """A back swipe from Settings slides the page away like the back arrow; it never shrinks into a card."""
    open_tab("Wallpaper")
    cw.tap("Settings")
    check(cw.has("Appearance", timeout=5), "Settings opens")
    time.sleep(0.6)
    rest = cw.d(text="Appearance").info["bounds"]
    w, h = cw.d.window_size()
    cw.d.touch.down(2, h // 2)
    for i in range(1, 9):
        cw.d.touch.move(int(w * 0.05 * i), h // 2)
        time.sleep(0.03)
    time.sleep(0.5)
    mid = cw.d(text="Appearance").info["bounds"]
    cw.d.screenshot("shots/suite/swipe-back-mid.jpg")
    cw.d.touch.up(int(w * 0.4), h // 2)
    log(f"Appearance at rest {rest}, mid-swipe {mid}")
    same_size = abs((mid["bottom"] - mid["top"]) - (rest["bottom"] - rest["top"])) <= 2
    check(same_size and abs(mid["top"] - rest["top"]) <= 4, f"mid-swipe the page keeps its size and height: {rest} -> {mid}")
    check(mid["left"] > rest["left"] + 4, f"and moves right with the finger: {rest['left']} -> {mid['left']}")
    check(cw.has("Library", timeout=4) and not cw.has("Appearance"), "letting go finishes the back: the tabs show again")


def t_shortcut():
    before = ids()
    cw.sh("shell", "am", "start", "-n", cw.SHORTCUT)
    check(wait_until(lambda: ids() != before, 10), f"shortcut changed the wallpaper ({before} -> {ids()})")


def t_explore():
    open_tab("Explore")
    check(cw.has("Search wallpapers", timeout=6), "Explore loads")
    tiles = [l for l in cw.labels() if l not in ("All", "Art", "Space", "Earth", "Explore", "Library", "Wallpaper", "Settings", "Account")]
    check(len(tiles) > 8, f"gallery has pictures ({len(tiles)} labels)")


def gallery_sizes() -> dict:
    """Each published wallpaper's title and original size, from the live gallery the app browses."""
    import json
    import urllib.request
    url = "https://wallpaper.connectionsapi.com/v1/wallpapers?limit=100"
    with urllib.request.urlopen(urllib.request.Request(url, headers={"accept": "application/json"}), timeout=20) as res:
        return {w["title"]: (w["width"], w["height"]) for w in json.load(res)["items"]}


def t_explore_set_signed_out():
    """Signed out, a gallery wallpaper sets at its full size (never the 640px preview); only Save to phone asks for a sign-in."""
    sizes = gallery_sizes()
    open_tab("Explore")
    check(cw.has("Search wallpapers", timeout=6), "Explore loads")
    title = next((l for l in cw.labels() if l in sizes), None)
    check(title is not None, "a gallery wallpaper is on screen")
    cw.tap(title)
    check(cw.has("Set wallpaper", timeout=6), f"the viewer opens on {title}")
    before = ids()
    cw.tap("Set wallpaper")
    cw.tap("Home and lock screens")
    check(wait_until(lambda: ids().get("system") != before.get("system"), 90), "the wallpaper is set, signed out")
    width, height = sizes[title]
    got = cw.wall_file_size("wallpaper_orig")
    check(max(got) >= min(1920, max(width, height)) and max(got) > 640, f"set at {got[0]}x{got[1]}, the original is {width}x{height}")
    cw.tap("Save to phone")
    check(cw.has("Sign in with Connections", timeout=6), "Save to phone asks a signed-out visitor to sign in")
    cw.d.press("back")


TESTS = [t_lock_first_folder, t_unlock_changes, t_timer_toggle, t_unlock_off, t_pause, t_effects,
         t_library_folder_fills_both, t_same_folder_numbered, t_home_fill_empty_slot, t_theme, t_swipe_back, t_shortcut, t_explore,
         t_explore_set_signed_out]


def unknown_tests(names: list[str]) -> list[str]:
    return sorted(set(names) - {t.__name__[2:] for t in TESTS})


def main() -> None:
    names = sys.argv[1:]
    unknown = unknown_tests(names)
    if unknown:
        sys.exit(f"no such test: {', '.join(unknown)}")
    cw.push_testwalls(FOLDER)  # before connecting: it restarts adbd as root
    cw.connect()
    results = []
    for test in TESTS:
        name = test.__name__[2:]
        if names and name not in names:
            continue
        start = time.time()
        print(f"> {name}", flush=True)
        try:
            test()
            results.append((name, "PASS", time.time() - start, ""))
        except Exception as e:  # noqa: BLE001 - the suite reports every failure and goes on
            try:
                cw.d.screenshot(f"shots/suite/FAIL-{name}.jpg")
                seen = cw.labels()
            except Exception:  # noqa: BLE001
                seen = []
            results.append((name, "FAIL", time.time() - start, f"{e}".strip()[:300]))
            print("".join(traceback.format_exception(e)[-2:])[:800])
            print(f"  on screen: {seen[:45]}")
    print("\n==== results ====")
    for name, verdict, secs, why in results:
        print(f"{verdict} {name:28s} {secs:5.1f}s {why}")
    print(f"{sum(r[1] == 'PASS' for r in results)} of {len(results)} passed, total {time.time() - T0:.1f}s")
    sys.exit(0 if results and all(r[1] == "PASS" for r in results) else 1)


if __name__ == "__main__":
    main()

r"""Connections Wallpaper end-to-end suite on the emulator. Each test prints PASS/FAIL with its time; a failure
saves shots/suite/FAIL-<test>.jpg and the labels on screen, then the run goes on.

  tools\emulator-suite\check.cmd                                (unit, lint, release build, device tests)
  adb -s emulator-5554 install -r app/build/outputs/apk/release/app-release.apk
  python -X utf8 tools/emulator-suite/suite.py [test ...]      (no names: every test, in order)
  python -X utf8 tools/emulator-suite/private.py [test ...]    (the same on a private emulator, when another session has 5554;
                                                               --apk <apk> runs another build, such as one from before a fix)
  python -X utf8 tools/emulator-suite/unlock_speed.py          (after lock_first_folder: screen-off to new lock picture)
  python -X utf8 tools/emulator-suite/phone_install.py --serial <phone> [--build-log <log>]
                                                               (install -r on a real phone whenever it is plugged in)

Each run wipes the app's data first (the same as a fresh install) and takes about six minutes.
"""
import os
import re
import sys
import tempfile
import time
import traceback
from xml.etree import ElementTree

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
    """Open a tab at its top: a tab keeps its scroll between visits, and a label lower down can share a name. A page
    over the tabs (Settings, which pick_mode leaves open, or an album) is backed out of first."""
    open_app()
    for _ in range(3):
        if cw.d(text="Library").exists:
            break
        cw.d.press("back")
        cw.d(text="Library").wait(timeout=3)
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
    if d(text="Not now").wait(timeout=8):  # a loaded PC's fresh emulator took over 3 s to show it
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


def switch_state(title: str, timeout: float = 5) -> bool:
    """The checked state of the switch on the row titled <title> (same height as the title). The title and the
    switches come from one screen dump: a row that has just appeared slides in, and two separate reads saw its title
    and its switch in different places. The dump is taken again until the row has its switch."""
    def top_bottom(node: ElementTree.Element) -> tuple[int, int]:
        _, top, _, bottom = map(int, re.findall(r"-?\d+", node.get("bounds", "")))
        return top, bottom

    end = time.time() + timeout
    while True:
        root = ElementTree.fromstring(cw.d.dump_hierarchy())
        rows = [n for n in root.iter() if n.get("text") == title]
        if rows:
            top, bottom = top_bottom(rows[0])
            cy = (top + bottom) // 2
            for n in root.iter():
                if n.get("checkable") == "true" and top_bottom(n)[0] <= cy <= top_bottom(n)[1]:
                    return n.get("checked") == "true"
        if time.time() > end:
            raise AssertionError(f"no switch beside {title!r}" if rows else f"no row {title!r}")
        time.sleep(0.3)


def scroll_to(text: str) -> None:
    if not cw.d(text=text).exists:
        cw.d(scrollable=True).scroll.to(text=text)


def tab_bottom_has(text: str, timeout: float = 4) -> bool:
    """Scroll the tab to its end, again until the timeout, so a row that just appeared below the fold counts."""
    def at_end() -> bool:
        # toEnd stops at the end; the Wallpaper tab with both albums takes more than five swipes on a 1080x2400 screen.
        cw.d(scrollable=True).scroll.toEnd(max_swipes=20)
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


def pick_mode(label: str) -> None:
    open_tab("Settings")
    cw.tap("Wallpaper mode")
    cw.tap(label)


def set_live_in_picker() -> None:
    """Accept the system live wallpaper preview the Live switch opens, on both screens where it asks."""
    d = cw.d
    d(textMatches="(?i)set wallpaper").click(timeout=10)
    both = d(textMatches="(?i)home (screen )?and lock screens?")
    if both.wait(timeout=3):
        both.click()


def live_is_ours(where: str = "system") -> bool:
    """Whether our live wallpaper shows on <where> (system or lock). Home always reports a component, so none there is
    a dumpsys the parse no longer reads, never an answer."""
    now = ids()
    component = now[f"{where}_component"]
    if where == "system" and component is None:
        raise AssertionError(f"no home wallpaper component in dumpsys ({now})")
    return cw.PKG in (component or "")


def live_anywhere() -> bool:
    return live_is_ours("system") or live_is_ours("lock")


def live_round_trip(home_rotates: bool) -> None:
    """Owner report (2026-10-21): live back to static kept every setting but rotated only after Change wallpaper
    now. The switch itself replaces our live wallpaper with a static picture (static mode cannot move it, so a screen
    left on it freezes), starts the unlock watcher, and the next unlock changes the picture."""
    pick_mode("Live wallpaper")
    set_live_in_picker()
    check(wait_until(live_is_ours, 12), f"our live wallpaper is the phone's ({ids()['component']})")
    # A lock screen sharing home's wallpaper has no section of its own (lock None).
    check(live_is_ours("lock") or ids()["lock"] is None, f"and the lock screen's ({ids()})")
    check(wait_until(lambda: not watcher_running(), 6), "live mode: no unlock watcher")
    before = ids()
    pick_mode("Static")
    check(wait_until(lambda: not live_anywhere(), 12), f"the switch leaves our live wallpaper on no screen ({before} -> {ids()})")
    check(wait_until(watcher_running, 8), "the switch starts the unlock watcher")
    unlock()
    start = ids()
    time.sleep(1.2)
    screen_off_on()
    # One picture on both screens is set as the system wallpaper alone (the lock id stays 1).
    changed = wait_until(lambda: ids()["lock"] != start["lock"] or ids()["system"] != start["system"], 8)
    unlock()
    check(changed, f"the first unlock after the switch changes the wallpaper ({start} -> {ids()})")
    if home_rotates:
        check(ids()["system"] != start["system"], f"home included ({start} -> {ids()})")


def t_live_to_static():
    live_round_trip(home_rotates=False)


def t_static_replaces_live():
    """Static mode with our live wallpaper put back on the phone (from its own picker): opening the app replaces it,
    and the lock screen, which rotates, keeps its picture (a full change there would skip one)."""
    lock_before = cw.wall_color("lock")
    cw.sh("shell", "am", "start", "-a", "android.service.wallpaper.CHANGE_LIVE_WALLPAPER", "--ecn",
          "android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT",
          f"{cw.PKG}/com.anthonyla.paperize.service.livewallpaper.PaperizeLiveWallpaperService")
    set_live_in_picker()
    check(wait_until(live_is_ours, 12), "our live wallpaper is back on the phone")
    # A fresh activity, never a force-stop: Android resets the wallpaper to its own default when the live wallpaper's
    # package is force-stopped, which passed this check with the app doing nothing (2026-10-21).
    cw.sh("shell", "am", "start", "-n", cw.MAIN, "-f", "0x10008000")
    check(wait_until(lambda: not live_anywhere(), 15), f"opening the app replaces it with a static picture ({ids()})")
    check(cw.wall_image("wallpaper").width > 640, "home has a picture of the app's, not Android's default (which has no file)")
    lock_after = cw.wall_color("lock")
    check(lock_after == lock_before, f"the lock screen keeps its picture ({lock_before} -> {lock_after})")


def t_live_to_static_both():
    live_round_trip(home_rotates=True)


def t_live_to_static_home_only():
    """Only home rotating: the switch also clears our live wallpaper off the lock screen, which Android 14+ lets keep a
    live wallpaper of its own when home alone is set."""
    open_tab("Wallpaper")
    cw.tap("Lock")
    check(wait_until(lambda: not cw.has("Lock album"), 4), "Lock is off, so home alone rotates")
    live_round_trip(home_rotates=True)
    open_tab("Wallpaper")
    cw.tap("Lock")
    check(cw.has("Lock album", timeout=4), "Lock is on again for the tests after")


def t_preview_opens_album():
    """Owner ask (2026-10-21): the lock screen's current picture opens its album inside Library, and back lands in
    Library."""
    open_tab("Wallpaper")
    scroll_to("Current wallpapers")
    cw.d(description="Current lock screen wallpaper").click(timeout=5)
    check(cw.d(description="Back").wait(timeout=5) and cw.has(FOLDER), "the lock picture opens its album")
    cw.d.screenshot("shots/suite/preview-opens-album.jpg")
    cw.d(description="Back").click()
    check(cw.has("Add album", timeout=5), "back lands in Library")


def notice_posted() -> bool:
    """Whether change on unlock's notice (notification 3) is posted."""
    out = cw.sh("shell", "dumpsys", "notification", "--noredact")
    return re.search(rf"NotificationRecord\(\S+ pkg={re.escape(cw.PKG)} user=\S+ id=3 ", out) is not None


def notice_importance() -> int:
    out = cw.sh("shell", "dumpsys", "notification", "--noredact")
    m = re.search(r"mId='change_on_unlock'.*?mImportance=(\d+)", out)
    if not m:
        raise AssertionError("no change_on_unlock channel in dumpsys notification")
    return int(m.group(1))


def t_unlock_notice():
    """Owner ask (2026-10-21): the change-on-unlock notice can go. Swiped away, it stays away when a setting changes;
    Settings opens Android's page for it, and hidden there the watcher still changes the wallpaper on unlock."""
    check(wait_until(notice_posted, 6), "the watcher's notice is posted")
    cw.d.open_notification()
    note = cw.d(text="Change on unlock is on")
    check(note.wait(timeout=5), "the notice is in the shade")
    # A swipe on the title alone is too short to dismiss it; a person's swipe crosses the row.
    b = note.info["bounds"]
    w = cw.d.info["displayWidth"]
    y = (b["top"] + b["bottom"]) // 2
    cw.d.swipe(int(w * 0.1), y, int(w * 0.95), y, duration=0.25)
    swiped = wait_until(lambda: not notice_posted(), 5)
    cw.d.press("back")
    check(swiped, "a swipe dismisses it")
    open_tab("Wallpaper")
    pid = cw.sh("shell", "pidof", cw.PKG).strip()
    cw.tap("Change on a timer")
    cw.tap("Change on a timer")
    # Each edit starts the running watcher again without a second startForeground: past Android's 10 s deadline for
    # one, the same process still runs it (a missed one kills the app).
    time.sleep(12)
    check(cw.sh("shell", "pidof", cw.PKG).strip() == pid and watcher_running(), f"the app ({pid}) and its watcher outlive the edits")
    check(not notice_posted(), "a settings change posts no notice again")
    open_tab("Settings")
    check(cw.has("Change on unlock notification", timeout=5) and cw.has("Shown"), "Settings shows the notice as Shown")
    cw.d.screenshot("shots/suite/settings-unlock-notice.jpg")
    cw.tap("Change on unlock notification")
    switch = cw.d(className="android.widget.Switch", checked=True)
    check(switch.wait(timeout=8), f"Android's page for the notice opens ({cw.labels()[:12]})")
    switch.click()
    check(wait_until(lambda: notice_importance() == 0, 5), "its switch turns the notice's channel off")
    cw.d.press("back")
    check(cw.has("Hidden", timeout=5), "back in Settings, the row says Hidden")
    unlock()
    before = ids()["lock"]
    time.sleep(1.2)
    screen_off_on()
    changed = wait_until(lambda: ids()["lock"] != before, 8)
    unlock()
    check(changed and watcher_running(), "hidden, the watcher still changes the lock screen on unlock")


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


def t_update_keeps_watcher():
    """An app update brings the unlock watcher back by itself. An update stops the app's process, and on 2026-10-21 the
    owner's phone went without change on unlock after one until the app was opened."""
    check(wait_until(watcher_running, 6), "unlock watcher running before the update")
    path = cw.sh("shell", "pm", "path", cw.PKG).split()[0].removeprefix("package:")
    with tempfile.TemporaryDirectory() as tmp:
        apk = os.path.join(tmp, "base.apk")
        cw.sh("pull", path, apk, check=True)
        out = cw.sh("install", "-r", apk)
    check("Success" in out, f"the same APK installed over itself ({out[-200:]})")
    check(wait_until(watcher_running, 30), "the watcher is back after the update, with the app never opened")


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
    with urllib.request.urlopen(urllib.request.Request(url, headers={"accept": "application/json", "user-agent": cw.UA}), timeout=20) as res:
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



def gallery_tags() -> dict:
    """Each gallery tag's live count, from the same door the app reads."""
    import json
    import urllib.request
    url = "https://wallpaper.connectionsapi.com/v1/tags"
    with urllib.request.urlopen(urllib.request.Request(url, headers={"accept": "application/json", "user-agent": cw.UA}), timeout=20) as res:
        return {t["tag"]: t["count"] for t in json.load(res)["tags"]}


def ready_app() -> None:
    """A collections test also runs on its own: an app that has not been set up yet is set up first."""
    cw.sh("shell", "am", "start", "-n", cw.MAIN)
    if cw.d(text="Agree").wait(timeout=4):
        cw.onboard()


def explore_shelf() -> None:
    ready_app()
    open_tab("Explore")
    check(cw.has("Search wallpapers", timeout=6), "Explore loads")
    check(cw.has("See all", timeout=10), "the Collections shelf shows above the pictures")


def kept_files() -> list[str]:
    """What the app keeps in its own storage for the gallery (files/connections, previews included); needs adb root."""
    out = cw.sh("shell", "ls", "-R", f"/data/data/{cw.PKG}/files/connections")
    return sorted(l.strip() for l in out.splitlines() if l.strip() and not l.endswith(":") and "No such file" not in l)


def new_collection_from_viewer(name: str) -> str:
    """Open a gallery picture, put it in a new collection named <name> from the bookmark, close the viewer."""
    sizes = gallery_sizes()
    title = next((l for l in cw.labels() if l in sizes), None)
    check(title is not None, "a gallery wallpaper is on screen")
    cw.tap(title)
    check(cw.has("Set wallpaper", timeout=6), f"the viewer opens on {title}")
    cw.tap("Save to collection")
    check(cw.has("New collection", timeout=5), "the bookmark opens Save to collection")
    cw.tap("New collection")
    field = cw.d(className="android.widget.EditText")
    check(field.wait(timeout=5), "New collection asks for a name")
    field.set_text(name)
    cw.tap("Save")
    check(cw.has(f"Added to {name}", timeout=6), f"the picture is in {name}")
    cw.d.press("back")
    check(wait_until(lambda: not cw.d(text="Set wallpaper").exists, 4), "one back closes the viewer (the name's keyboard is already down)")
    return title


def t_collections_browse():
    """Explore's shelf opens the manager (Yours, then the gallery's tags), and a tag's page shows its live count."""
    tags = gallery_tags()
    explore_shelf()
    cw.tap("See all")
    check(cw.has("New collection", timeout=6) and cw.has("Yours"), "See all opens the manager with Yours and New collection")
    for tag in ("Art", "Space", "Japan"):
        if not cw.has(tag):
            cw.d(scrollable=True).scroll.to(text=tag)
        check(cw.has(tag, timeout=4), f"the manager shows the gallery's {tag}")
    cw.d(scrollable=True).scroll.toBeginning(max_swipes=10)
    if not cw.has("Art"):
        cw.d(scrollable=True).scroll.to(text="Art")
    cw.tap("Art")
    count = tags["art"]
    check(cw.has(f"{count} wallpaper", timeout=8), f"Art's page says {count} wallpapers, as the gallery does")
    check(cw.has("Save offline"), "and offers Save offline")
    cw.d.press("back")
    cw.d.press("back")
    check(cw.has("See all", timeout=6), "back returns to Explore")


def t_collection_own():
    """Anyone, signed out, makes a collection from a picture's bookmark; it shows on the shelf, renames, and deletes."""
    explore_shelf()
    new_collection_from_viewer("Beach Days")
    # Exact text: cw.has matches by contains, and the "Added to Beach Days" note would pass for the card.
    check(cw.d(text="Beach Days").wait(timeout=6), "the new collection leads the shelf, in sight")
    cw.tap("Beach Days")
    check(cw.has("1 wallpaper", timeout=6), "its page holds the one picture")
    cw.tap("More options")
    cw.tap("Rename")
    field = cw.d(className="android.widget.EditText")
    check(field.wait(timeout=5), "Rename asks for the new name")
    field.set_text("Beach")
    cw.tap("Save")
    check(wait_until(lambda: cw.d(text="Beach").exists, 6), "the page takes the new name")
    cw.tap("More options")
    cw.tap("Delete")
    check(cw.has("Delete Beach?", timeout=5), "Delete asks first")
    cw.d(text="Delete").click()
    check(cw.has("See all", timeout=8), "deleting goes back to Explore")
    check(wait_until(lambda: not cw.d(text="Beach").exists, 6), "and the collection is gone from the shelf")


def t_collection_offline_signed_out():
    """Signed out, Save offline asks for the free account in the app's own sheet and keeps nothing on the phone."""
    explore_shelf()
    before = kept_files()
    # Explore's Art chip shares the shelf card's label; the manager has only the card.
    cw.tap("See all")
    if not cw.has("Art", timeout=4):
        cw.d(scrollable=True).scroll.to(text="Art")
    cw.tap("Art")
    cw.tap("Save offline")
    check(cw.has("Save Art offline", timeout=6), "Save offline asks for the account, named for what it saves")
    check(cw.has("Sign in with Connections"), "with the app's own Sign in with Connections button")
    cw.d.press("back")
    time.sleep(2)
    after = kept_files()
    check(after == before, f"nothing was fetched while signed out ({len(before)} files before, {len(after)} after)")
    cw.d.press("back")
    cw.d.press("back")


def t_collection_offline_opens():
    """A collection of the member's own opens with no internet: the shelf and its page come from the phone."""
    explore_shelf()
    title = new_collection_from_viewer("Offline Pick")
    cw.sh("shell", "cmd", "connectivity", "airplane-mode", "enable")
    try:
        cw.sh("shell", "am", "force-stop", cw.PKG)
        time.sleep(1)
        cw.sh("shell", "am", "start", "-n", cw.MAIN)
        open_tab("Explore")
        check(cw.d(text="Offline Pick").wait(timeout=10), "with no internet, the shelf still shows the collection")
        cw.tap("Offline Pick")
        check(cw.has("1 wallpaper", timeout=6), "its page opens")
        check(cw.has(title, timeout=4), f"with its picture, {title}")
    finally:
        cw.sh("shell", "cmd", "connectivity", "airplane-mode", "disable")
    cw.d.press("back")


TESTS = [t_lock_first_folder, t_unlock_changes, t_preview_opens_album, t_unlock_notice, t_live_to_static, t_static_replaces_live,
         t_timer_toggle, t_unlock_off, t_pause, t_update_keeps_watcher, t_effects, t_library_folder_fills_both, t_live_to_static_both, t_live_to_static_home_only,
         t_same_folder_numbered, t_home_fill_empty_slot, t_theme, t_swipe_back, t_shortcut, t_explore, t_explore_set_signed_out,
         t_collections_browse, t_collection_own, t_collection_offline_signed_out, t_collection_offline_opens]


def unknown_tests(names: list[str]) -> list[str]:
    return sorted(set(names) - {t.__name__[2:] for t in TESTS})


def main() -> None:
    names = sys.argv[1:]
    unknown = unknown_tests(names)
    if unknown:
        sys.exit(f"no such test: {', '.join(unknown)}")
    cw.push_testwalls(FOLDER)  # before connecting: it restarts adbd as root
    cw.connect()
    # A CPU-starved emulator raises "System UI isn't responding" over every screen; Wait lets the system catch up.
    cw.d.watcher.when("%responding%").when("Wait").click()
    cw.d.watcher.start(1.0)
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

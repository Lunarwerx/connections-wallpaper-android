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
import json
import os
import re
import sys
import tempfile
import time
import traceback
import urllib.parse
import urllib.request
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


def pick_folder(folder: str = FOLDER, first: bool = False) -> None:
    """Drive the system folder picker to Pictures/<folder> and allow it. The [first] album a signed-out install adds
    brings the one-time free-account note, which this dismisses; later albums bring none, so they do not wait for it."""
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
    # The suite stays signed out. A fresh emulator on a PC busy with a Gradle build took over 8 s to show the note.
    if first and d(text="Not now").wait(timeout=20):
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


def tab_top_has(text: str, timeout: float = 8) -> bool:
    """Scroll the tab to its start, again until the timeout: a tab still sliding in has no scrollable for a moment, and
    open_tab's one scroll then misses (2026-10-23: the Wallpaper tab stayed scrolled past Lock and Home)."""
    def at_start() -> bool:
        page = cw.d(scrollable=True)
        if page.exists:
            page.scroll.toBeginning(max_swipes=20)
        return cw.has(text)
    return wait_until(at_start, timeout)


def tab_bottom_has(text: str, timeout: float = 8) -> bool:
    """Scroll the tab to its end, again until the timeout, so a row that just appeared below the fold counts."""
    def at_end() -> bool:
        # toEnd stops at the end; the Wallpaper tab with both albums takes more than five swipes on a 1080x2400 screen.
        # A tab still sliding in (back from a page) has no scrollable for a moment: that try counts as not there yet.
        page = cw.d(scrollable=True)
        if page.exists:
            page.scroll.toEnd(max_swipes=20)
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
    pick_folder(first=True)
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
    # Review (2026-10-22): paused, a look edit leaves the phone as it is and the header says why; resuming draws it.
    to_scaling()
    check(cw.has("Wallpaper changing is off", timeout=4), "paused, the Scaling header says changing is off")
    before = ids()
    cw.tap("Fit")
    time.sleep(2)
    check(ids() == before, f"paused, the edit leaves the phone as it is ({before} -> {ids()})")
    open_tab("Wallpaper")
    cw.tap("Wallpaper changing")
    check(wait_until(watcher_running, 6), "resuming starts it again")
    check(wait_until(lambda: ids()["system"] != before["system"] or ids()["lock"] != before["lock"], 15),
          f"resuming draws the look edited while paused ({before} -> {ids()})")
    to_scaling()
    check(wait_until(lambda: not cw.has("Applying") and not cw.has("Applied"), 15), "the redraw's status settles")
    cw.tap("Fill")
    check(cw.has("Applied", timeout=12), "with changing on, a scaling edit says Applied")
    open_tab("Wallpaper")
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
    pick_folder(first=True)
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
    """Home's album deleted, Lock's kept: a new album from Library fills only the empty slot. A screen switched off keeps
    its album (e7a7761), so the empty slot is made the way a person makes one: by deleting the album Home uses."""
    second = f"{FOLDER} 2"
    open_tab("Wallpaper")
    check(tab_top_has("Home album"), "the Home album row is on screen")
    cw.tap("Home album")
    cw.tap(second)
    cw.d.press("back")
    check(wait_until(lambda: cw.labels().count(second) == 1, 6), f"Home uses {second}, Lock keeps {FOLDER}")
    open_tab("Library")
    cw.tap(second)
    cw.tap("Delete")
    cw.tap("Confirm")
    check(wait_until(lambda: not cw.has(second), 10), f"{second} is deleted")
    open_tab("Wallpaper")
    check(tab_top_has("No album selected"), "home has no album")
    check(FOLDER in cw.labels(), f"lock keeps {FOLDER}")
    open_tab("Library")
    cw.tap("Add album")
    cw.tap("Add folder")
    pick_folder()
    check(cw.has(second, timeout=10), f"the new album is {second} again: the name is free")
    open_tab("Wallpaper")
    check(tab_top_has(second), f"{second} is on the Wallpaper tab")
    labels = cw.labels()
    check(second in labels and FOLDER in labels and not cw.has("No album selected"), f"the empty home slot took the new album, lock kept its own: {labels[5:25]}")


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
    # 10 s: the redraw re-themes the app from the new wallpaper's colours, which recreates this screen (slow when busy).
    check(wait_until(lambda: tab_bottom_has("Visual effects", 1) and not cw.has("Adjust visual effects"), 10),
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
    # The wall fills after its first page arrives; read it with a deadline, not once.
    tiles = lambda: [l for l in cw.labels() if l not in ("All", "Art", "Space", "Earth", "Explore", "Library", "Wallpaper", "Settings", "Account")]
    check(wait_until(lambda: len(tiles()) > 8, 15), f"gallery has pictures ({len(tiles())} labels)")


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


def scaling_thumb() -> str:
    """The test colour the Scaling picker's Fill phone shows: the live wallpaper's picture now, drawn in the app."""
    b = cw.d(text="Fill").info["bounds"]
    # The phone stands above its label; 120 px up is well inside its picture on a 1080x2400 screen.
    return cw.nearest(cw.d.screenshot().convert("RGB").getpixel(((b["left"] + b["right"]) // 2, b["top"] - 120)))


def to_scaling() -> None:
    page = cw.d(scrollable=True)
    page.scroll.toBeginning(max_swipes=20)
    page.scroll.to(text="Fill")


def t_explore_set_keeps_live():
    """Owner report (2026-10-22): in live mode a gallery picture set on both screens replaced our live wallpaper, so
    rotation stopped and Change wallpaper now did nothing. Our live wallpaper now stays on and shows the picture, and
    Change wallpaper now moves on from it."""
    sizes = gallery_sizes()
    pick_mode("Live wallpaper")
    set_live_in_picker()
    check(wait_until(live_is_ours, 12), f"our live wallpaper is the phone's ({ids()['component']})")
    # Until it draws its album's picture the screen still shows the last flow's gallery picture, the one set below.
    check(wait_until(lambda: cw.home_color() in cw.COLORS, 30), f"it shows its album's picture ({cw.home_color()})")
    before = cw.home_color()
    cw.sh("shell", "am", "start", "-n", cw.MAIN)
    open_tab("Explore")
    check(cw.has("Search wallpapers", timeout=6), "Explore loads")
    title = next((l for l in cw.labels() if l in sizes), None)
    check(title is not None, "a gallery wallpaper is on screen")
    cw.tap(title)
    cw.tap("Set wallpaper")
    cw.tap("Home and lock screens")
    check(wait_until(lambda: cw.home_color() != before, 20), f"our live wallpaper shows the picture ({before})")
    shown = cw.home_color()
    check(live_is_ours(), f"and stays the phone's ({ids()})")
    cw.sh("shell", "am", "start", "-n", cw.MAIN)
    if cw.has("Set wallpaper", timeout=3):
        cw.d.press("back")
    open_tab("Wallpaper")
    check(switch_state("Wallpaper changing"), "rotation stays on")
    to_scaling()
    thumb = scaling_thumb()
    check(tab_bottom_has("Change wallpaper now"), "Change wallpaper now ends the tab")
    cw.tap("Change wallpaper now")
    check(cw.has("Changed", timeout=6), "the button says Changed")
    # The app's window hides the wallpaper: the next picture must still come at once, not when the home screen shows.
    to_scaling()
    check(wait_until(lambda: scaling_thumb() not in (thumb, "other"), 15), f"the app shows the next picture at once ({thumb} -> {scaling_thumb()})")
    cw.tap("Fit")
    check(cw.has("Applied", timeout=6), "a live scaling change says Applied")
    cw.tap("Fill")
    check(wait_until(lambda: cw.home_color() in cw.COLORS, 20), f"Change wallpaper now moves on from the picture to the album's ({shown})")
    check(live_is_ours(), f"with our live wallpaper still the phone's ({ids()})")
    cw.sh("shell", "am", "start", "-n", cw.MAIN)
    pick_mode("Static")
    check(wait_until(lambda: not live_anywhere(), 12), "static mode again for the tests after")



def gallery_tags() -> dict:
    """Each gallery tag's live count, from the same door the app reads."""
    import json
    import urllib.request
    url = "https://wallpaper.connectionsapi.com/v1/tags"
    with urllib.request.urlopen(urllib.request.Request(url, headers={"accept": "application/json", "user-agent": cw.UA}), timeout=20) as res:
        return {t["tag"]: t["count"] for t in json.load(res)["tags"]}


def explore_shelf() -> None:
    """Explore from a fresh install: the collection flows leave their collections behind, and a name one took is refused."""
    cw.fresh_app()
    cw.onboard()
    open_tab("Explore")
    check(cw.has("Search wallpapers", timeout=6), "Explore loads")
    check(cw.has("See all", timeout=10), "the Collections shelf shows above the pictures")


def kept_files() -> list[str]:
    """What the app keeps in its own storage for the gallery (files/connections, previews included); needs adb root."""
    out = cw.sh("shell", "ls", "-R", f"/data/data/{cw.PKG}/files/connections")
    return sorted(l.strip() for l in out.splitlines() if l.strip() and not l.endswith(":") and "No such file" not in l)


def enter_name(opener: str, name: str, what: str, saved=None) -> None:
    """Tap opener (a sheet row or menu item), type into the name dialog it opens and press Save, until the dialog's own
    result shows: the field, then the dialog gone and, for a sheet's row, the sheet gone with it. Under load the keyboard
    opens late and moves the dialog, so a click placed from the field's earlier bounds lands on the scrim and dismisses
    the dialog; the sequence then starts again from the opener."""
    edits = cw.d(className="android.widget.EditText")
    save = cw.d(text="Save")
    row = cw.d(text=opener)
    saved = saved or (lambda: not row.exists)  # a page may show the same words as the row (Explore has a New collection tile)
    # The dialog is the window with a Save button; its field is the last EditText in the dump (Explore's search field is
    # an EditText too and comes first), so a retry can never type into the page behind the dialog.
    dialog = lambda: save.exists and edits.exists
    end = time.time() + 120
    seen = pressed = False
    while time.time() < end:
        try:
            if dialog():
                seen = True
                time.sleep(1.5)  # let the keyboard settle before typing into the field
                cw.d(className="android.widget.EditText", instance=edits.count - 1).set_text(name)
                cw.tap("Save")
                pressed = True
                if wait_until(lambda: not dialog() and saved(), 8):
                    return
            elif pressed:
                # Save closed the dialog: wait for its result and never type again unless the dialog is back
                if wait_until(lambda: dialog() or saved(), 8) and not dialog():
                    return
                if not dialog():
                    pressed = False  # the dialog went without saving (a scrim tap): start again from the opener
            else:
                cw.tap(opener)  # one tap, then wait: a second tap while the dialog opens would close it
                wait_until(dialog, 20)
        except Exception:  # noqa: BLE001 - uiautomator2 reports a redrawn node as an RPC error
            time.sleep(1)
    check(seen, what)
    check(False, "the name dialog took the name and Save closed it")


def new_collection_from_viewer(name: str) -> str:
    """Open a gallery picture, put it in a new collection named <name> from the bookmark, close the viewer."""
    sizes = gallery_sizes()
    title = next((l for l in cw.labels() if l in sizes), None)
    check(title is not None, "a gallery wallpaper is on screen")
    cw.tap(title)
    check(cw.has("Set wallpaper", timeout=6), f"the viewer opens on {title}")
    cw.tap("Save to collection")
    check(cw.has("New collection", timeout=5), "the bookmark opens Save to collection")
    enter_name("New collection", name, "New collection asks for a name")
    check(cw.has(f"Added to {name}", timeout=6), f"the picture is in {name}")
    cw.d.press("back")
    check(wait_until(lambda: not cw.d(text="Set wallpaper").exists, 4), "one back closes the viewer (the name's keyboard is already down)")
    return title


def first_gallery_tag() -> str:
    """The first tag the manager lists under From the gallery; the live gallery's tags change, so no test names one."""
    check(cw.has("From the gallery", timeout=8), "the manager has a From the gallery list")
    labels = cw.labels()
    return labels[labels.index("From the gallery") + 1]


def t_collections_browse():
    """Explore's shelf opens the manager (Yours, then the gallery's tags), and a tag's page shows its live count."""
    tags = gallery_tags()
    explore_shelf()
    cw.tap("See all")
    check(cw.has("New collection", timeout=6) and cw.has("Yours"), "See all opens the manager with Yours and New collection")
    tag = first_gallery_tag()
    check(tag.lower() in tags, f"the manager lists the gallery's {tag} first")
    cw.tap(tag)
    count = tags[tag.lower()]
    check(cw.has(f"{count} wallpaper", timeout=8), f"{tag}'s page says {count} wallpapers, as the gallery does")
    check(cw.has("Add to library", timeout=10), "and offers Add to library")
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
    enter_name("Rename", "Beach", "Rename asks for the new name")
    check(wait_until(lambda: cw.d(text="Beach").exists, 6), "the page takes the new name")
    cw.tap("More options")
    cw.tap("Delete")
    check(cw.has("Delete Beach?", timeout=5), "Delete asks first")
    cw.d(text="Delete").click()
    check(cw.has("See all", timeout=8), "deleting goes back to Explore")
    check(wait_until(lambda: not cw.d(text="Beach").exists, 6), "and the collection is gone from the shelf")


def t_collection_offline_signed_out():
    """Signed out, a collection's Add to library asks for the free account in the app's own sheet and keeps nothing on the phone."""
    explore_shelf()
    before = kept_files()
    # Explore's tag chips share the shelf card's label; the manager has only the card.
    cw.tap("See all")
    tag = first_gallery_tag()
    cw.tap(tag)
    cw.tap("Add to library")
    check(cw.has(f"Add {tag} to your library", timeout=6), "Add to library asks for the account, named for what it adds")
    check(cw.has("Sign in with Connections"), "with the app's own Sign in with Connections button")
    cw.d.press("back")
    time.sleep(2)
    after = kept_files()
    check(after == before, f"nothing was fetched while signed out ({len(before)} files before, {len(after)} after)")
    cw.d.press("back")
    cw.d.press("back")


def t_collections_select():
    """A long press on a collection card picks it, in the manager and on Explore's shelf; taps toggle; signed out, Add to
    library asks for the account, named for what it adds, and fetches nothing; Delete is only for the member's own."""
    tags = gallery_tags()
    explore_shelf()
    before = kept_files()
    cw.tap("See all")
    check(cw.has("From the gallery", timeout=8), "the manager has a From the gallery list")
    labels = cw.labels()
    names = [l for l in labels[labels.index("From the gallery") + 1:] if l.lower() in tags]
    check(len(names) >= 2, "the manager shows at least two gallery collections")
    first, second = names[0], names[1]
    cw.d(text=first).long_click()
    check(cw.has("1 selected", timeout=5), "a long press picks the collection and the bar counts it")
    check(not cw.d(description="Delete").exists, "Delete is not offered for the gallery's collections")
    cw.tap(second)
    check(cw.has("2 selected", timeout=5), "a tap on another collection picks it too")
    cw.tap(second)
    check(cw.has("1 selected", timeout=5) and not cw.has("2 selected"), "a tap on a picked collection takes it back out")
    cw.tap(second)
    check(cw.has("2 selected", timeout=5), "and a tap picks it again")
    cw.tap("Add to library")
    check(cw.has("Add 2 collections to your library", timeout=6), "signed out, Add to library asks for the account, named for what it adds")
    check(cw.has("Sign in with Connections"), "with the app's own Sign in with Connections button")
    cw.d.press("back")
    time.sleep(2)
    check(kept_files() == before, "nothing was fetched while signed out")
    check(wait_until(lambda: not cw.d(textContains="selected").exists, 4), "and the selection ended with the add")
    cw.d.press("back")
    check(cw.has("See all", timeout=6), "back returns to Explore")
    # A shelf card's count line: the tag chips above share the card's name, but no chip shows a count.
    card = cw.d(textMatches=r"^\d+ wallpapers?$")
    check(card.wait(timeout=8), "Explore's shelf shows its collections")
    card.long_click()
    check(cw.has("1 selected", timeout=5), "on Explore's shelf, a long press picks a collection too")
    cw.d.press("back")
    check(wait_until(lambda: not cw.d(textContains="selected").exists, 4), "and back leaves selection mode")


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


def t_add_to_library_signed_out():
    """Signed out, Add to library takes a new album's name, then asks for the free account in the app's own sheet; nothing is fetched."""
    explore_shelf()
    before = kept_files()
    sizes = gallery_sizes()
    title = next((l for l in cw.labels() if l in sizes), None)
    check(title is not None, "a gallery wallpaper is on screen")
    cw.tap(title)
    check(cw.has("Set wallpaper", timeout=6), f"the viewer opens on {title}")
    cw.tap("Add to library")
    check(cw.has("New album", timeout=5), "Add to library opens the album list with New album")
    enter_name("New album", "Phone Picks", "New album asks for a name",
               saved=lambda: cw.d(textContains="Sign in to add to your library").exists)
    check(cw.has("Sign in to add to your library", timeout=40), "signed out, the account sheet opens titled for this action")
    check(cw.has("Sign in with Connections"), "with the app's own Sign in with Connections button")
    cw.d.press("back")
    time.sleep(2)
    check(kept_files() == before, "nothing was fetched while signed out")
    cw.d.press("back")
    check(wait_until(lambda: not cw.d(text="Set wallpaper").exists, 4), "one back closes the viewer")


def t_multi_select():
    """A long press on the Explore wall picks that picture; taps toggle; Add to collection puts the picked ones in a new collection."""
    explore_shelf()
    sizes = gallery_sizes()
    titles = [l for l in cw.labels() if l in sizes]
    check(len(titles) >= 2, "the wall shows at least two pictures")
    first, second = titles[0], titles[1]
    check(cw.d(description=first).wait(timeout=6), f"{first} is on the wall")
    cw.d(description=first).long_click()
    check(cw.has("1 selected", timeout=5), "a long press picks the picture and the bar counts it")
    cw.tap(second)
    check(cw.has("2 selected", timeout=5), "a tap on another picture picks it too")
    cw.tap(first)
    check(cw.has("1 selected", timeout=5) and not cw.has("2 selected"), "a tap on a picked picture takes it back out")
    cw.tap(first)
    check(cw.has("2 selected", timeout=5), "and a tap picks it again")
    cw.tap("Add to collection")
    enter_name("New collection", "Picked Set", "New collection asks for a name",
               saved=lambda: not cw.d(textContains="selected").exists)
    check(cw.has("Added to Picked Set", timeout=6), "the picked pictures go into Picked Set")
    check(wait_until(lambda: not cw.d(textContains="selected").exists, 4), "and the selection ends with the add")
    cw.tap("Picked Set")
    check(cw.has("2 wallpapers", timeout=6), "its page holds both picked pictures")
    cw.d.press("back")
    check(cw.has("See all", timeout=6), "back returns to Explore")


def chrome_opens_links() -> None:
    """Chrome opens a link at once, with no first-run screens: it reads this file on a debuggable build (the emulator)."""
    check("package:" in cw.sh("shell", "pm", "path", "com.android.chrome"), "the emulator has Chrome")
    cw.sh("shell", "sh", "-c", "'echo \"_ --disable-fre --no-default-browser-check --no-first-run\" > /data/local/tmp/chrome-command-line"
                                " && chmod 644 /data/local/tmp/chrome-command-line'")
    cw.sh("shell", "am", "set-debug-app", "--persistent", "com.android.chrome")
    cw.sh("shell", "am", "force-stop", "com.android.chrome")


def pending_sign_in_state(timeout: float = 20):
    """The state the app sent to AEGIS for the sign-in it started, as AEGIS hands it to the callback page: read from the
    app's own store (adb root). A fixed build keeps the bare token and sends it behind app-handoff.wallpaper.; the old
    build kept and sent the prefixed one; either way the callback gets app-handoff.wallpaper.<token>."""
    store = f"/data/data/{cw.PKG}/files/datastore/connections_account.preferences_pb"

    def find():
        raw = cw.sh("exec-out", "cat", store).encode("utf-8", "replace")
        found = re.search(rb"pending_state.{1,16}?(?:app-handoff\.wallpaper\.)?([A-Za-z0-9_-]{16,})", raw, re.S)
        return "app-handoff.wallpaper." + found.group(1).decode() if found else None
    return wait_until(find, timeout, every=1)


def t_sign_in_handoff():
    """Sign in with Connections comes back through the live callback page, and the app redeems what it brings. The
    code is made up, so Connections refuses it and the app says why; what this proves is that the state the browser
    carries finds the sign-in the app started (fails on 1.0.924, passes from 97386d8). From 858b361 every sign-in did nothing: the page hands the state back
    without its app-handoff.wallpaper. prefix, the app compared it with the prefixed one, and said nothing."""
    chrome_opens_links()
    cw.fresh_app()
    cw.onboard()
    cw.d(description="Account").click()
    cw.tap("Sign in with Connections")
    state = pending_sign_in_state()
    check(state, "the app started a sign-in and keeps its state")
    # Chrome's first screens ask about notifications over the page; the flow drives the page, not Chrome's setup.
    if cw.d(text="No thanks").wait(timeout=8):
        cw.d(text="No thanks").click()
    callback = "https://wallpaper.connections.icu/aegis/callback?" + urllib.parse.urlencode(
        {"state": state, "code": "emulator-suite-not-a-real-code"})
    # Quoted: adb runs the command through the device's shell, where & would end it.
    cw.sh("shell", "am", "start", "-a", "android.intent.action.VIEW", "-p", "com.android.chrome", "-d", f"'{callback}'")
    # The page opens the app's link itself; a browser may hold back a link nobody tapped, so the page offers it as a button.
    if not wait_until(lambda: cw.d.app_current().get("package") == cw.PKG, 10):
        if cw.d(textContains="Open Wallpaper").wait(timeout=10):
            cw.d(textContains="Open Wallpaper").click()
    check(cw.has("Sign-in didn't finish", timeout=40), "the app took the code back and tried it at Connections")
    check(not cw.has("earlier attempt"), "the state the browser carried matched the sign-in the app started")
    log(f"Connections refused the made-up code: {next((l for l in cw.labels() if 'finish' in l), '')}")
    cw.d.press("back")


def library_pictures(n: int) -> list[dict]:
    """The first n published wallpapers, as the live gallery lists them."""
    url = f"https://wallpaper.connectionsapi.com/v1/wallpapers?limit={n}"
    with urllib.request.urlopen(urllib.request.Request(url, headers={"accept": "application/json", "user-agent": cw.UA}), timeout=20) as res:
        return json.load(res)["items"][:n]


def add_streamed_album(name: str, pictures: list[dict]) -> None:
    """Puts gallery pictures in a new album as Add to library leaves them (by reference, nothing on the phone), straight
    into the app's database: the emulator has no Connections account to add them with. Stops the app; needs adb root."""
    app = f"/data/data/{cw.PKG}"
    now = int(time.time() * 1000)
    q = lambda text: text.replace("'", "''")
    rows = [f"INSERT INTO albums (id, name, coverUri, createdAt, modifiedAt) VALUES ('streamed', '{q(name)}', NULL, {now}, {now});"]
    for i, picture in enumerate(pictures):
        uri = f"content://{cw.PKG}.pictures/{picture['id']}/{picture['slug']}?preview=" + urllib.parse.quote(picture["previewUrl"], safe="")
        rows.append("INSERT INTO wallpapers (id, albumId, folderId, uri, fileName, dateModified, displayOrder, sourceType, addedAt, mediaType) "
                    f"VALUES ('streamed-{i}', 'streamed', NULL, '{q(uri)}', '{q(picture['title'])}', {now}, {i}, 'DIRECT', {now}, 'IMAGE');")
    local = os.path.join(tempfile.gettempdir(), "cwall-streamed.sql")
    with open(local, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(rows) + "\n")
    cw.sh("shell", "am", "force-stop", cw.PKG)
    cw.sh("push", local, "/data/local/tmp/cwall-streamed.sql")
    out = cw.sh("shell", f"sqlite3 {app}/databases/paperize_database < /data/local/tmp/cwall-streamed.sql && "
                         f"chown -R $(stat -c %u:%g {app}) {app}/databases && echo injected")
    check("injected" in out, f"the album went into the app's database ({out.strip()[-200:]})")


def screen_copies() -> list[str]:
    """The screen copies the app keeps of Library pictures it has shown (files/connections/screen); needs adb root."""
    out = cw.sh("shell", "ls", f"/data/data/{cw.PKG}/files/connections/screen")
    return sorted(l.strip() for l in out.splitlines() if l.strip() and "No such file" not in l)


def t_library_picture_streams():
    """Owner (2026-10-23): "it should just instantly add ... I didn't choose download". A gallery picture in an album is
    held by reference: nothing is on the phone until the wallpaper first shows it, then its compact screen copy is kept,
    the next picture is fetched ahead of its change, and a picture fetched once is never fetched again."""
    pictures = library_pictures(3)
    cw.fresh_app()
    cw.onboard()
    add_streamed_album("Streamed", pictures)
    open_tab("Library")
    cw.tap("Streamed")
    time.sleep(3)
    check(screen_copies() == [], f"the album's thumbnails are the gallery's previews: nothing was fetched ({screen_copies()})")
    open_tab("Wallpaper")
    check(tab_top_has("Lock"), "the Lock row is on screen")
    if cw.has("Home album"):
        cw.tap("Home")  # the lock screen alone: home would need an album of its own
    if not cw.has("Lock album"):
        cw.tap("Lock")
    cw.tap("Lock album")
    cw.tap("Streamed")
    cw.d.press("back")
    if not switch_state("Wallpaper changing"):
        cw.tap("Wallpaper changing")
    before = ids()
    check(tab_bottom_has("Change wallpaper now"), "Change wallpaper now ends the tab")
    cw.tap("Change wallpaper now")
    check(wait_until(lambda: ids()["lock"] not in (None, before["lock"]), 40), f"the lock screen shows the album, fetched as it went up ({before} -> {ids()})")
    check(wait_until(lambda: len(screen_copies()) == 2, 30), f"the next picture was fetched ahead of its change ({screen_copies()})")
    check(all(name.endswith(".webp") for name in screen_copies()), f"each is the compact WebP screen copy ({screen_copies()})")
    for turn in range(2):
        shown = ids()["lock"]
        check(tab_bottom_has("Change wallpaper now"), "Change wallpaper now ends the tab")
        cw.tap("Change wallpaper now")
        check(wait_until(lambda: ids()["lock"] != shown, 30), f"change {turn + 1} shows the next picture ({ids()})")
    time.sleep(5)
    check(len(screen_copies()) == 3, f"three pictures, three fetches, however often they show ({screen_copies()})")


TESTS = [t_lock_first_folder, t_unlock_changes, t_preview_opens_album, t_unlock_notice, t_live_to_static, t_static_replaces_live,
         t_timer_toggle, t_unlock_off, t_pause, t_update_keeps_watcher, t_effects, t_library_folder_fills_both, t_live_to_static_both, t_live_to_static_home_only,
         t_same_folder_numbered, t_home_fill_empty_slot, t_theme, t_swipe_back, t_shortcut, t_explore, t_explore_set_signed_out, t_explore_set_keeps_live,
         t_collections_browse, t_collection_own, t_collection_offline_signed_out, t_collections_select, t_collection_offline_opens, t_multi_select,
         t_add_to_library_signed_out, t_sign_in_handoff, t_library_picture_streams]


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
    # A system app crashing on the emulator ("Bluetooth keeps stopping", 2026-10-23) covers the app's first screen.
    cw.d.watcher.when("%keeps stopping%").when("Close app").click()
    cw.d.watcher.start(1.0)
    # The AVD keeps its data across boots, so a run stopped inside an offline flow left it offline for every run after.
    cw.sh("shell", "cmd", "connectivity", "airplane-mode", "disable")
    results = []
    for test in TESTS:
        name = test.__name__[2:]
        if names and name not in names:
            continue
        start = time.time()
        print(f"> {name}", flush=True)
        cw.sh("logcat", "-c")
        try:
            test()
            results.append((name, "PASS", time.time() - start, ""))
        except Exception as e:  # noqa: BLE001 - the suite reports every failure and goes on
            try:
                cw.d.screenshot(f"shots/suite/FAIL-{name}.jpg")
                # The flow's log beside its screenshot: why a set or a switch did nothing is in the app's warnings.
                with open(f"shots/suite/FAIL-{name}.log", "w", encoding="utf-8") as out:
                    out.write(cw.sh("logcat", "-d", "-v", "time"))
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

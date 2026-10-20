"""How long after the screen turns off the new lock wallpaper is in place: the time a person has before
turning the screen back on shows the old picture. Needs the app set up with a lock album and Change on
unlock on (the suite's lock_first_folder test leaves it so).

  python -X utf8 tools/emulator-suite/unlock_speed.py [rounds]
"""
import re
import statistics
import sys
import time
from datetime import datetime

import cw

LOCK_FILE = "/data/system/users/0/wallpaper_lock"


def screen_off_at() -> float:
    """The device time of the last screen-off, from the events log."""
    out = cw.sh("logcat", "-d", "-b", "events", "-v", "epoch", "-s", "screen_toggled")
    stamps = [float(m.group(1)) for m in re.finditer(r"^\s*(\d+\.\d+).*screen_toggled: 0", out, re.M)]
    return stamps[-1] if stamps else float("nan")


def lock_written_at() -> float:
    """The device time the lock wallpaper file was last written."""
    out = cw.sh("shell", "stat", "-c", "%y", LOCK_FILE).strip()
    m = re.match(r"(\d+-\d+-\d+ \d+:\d+:\d+)(?:\.(\d+))? ([+-]\d{4})", out)
    if not m:
        raise RuntimeError(f"stat said {out!r}")
    whole = datetime.strptime(f"{m.group(1)} {m.group(3)}", "%Y-%m-%d %H:%M:%S %z").timestamp()
    return whole + float(f"0.{m.group(2) or 0}")


def main() -> None:
    rounds = int(sys.argv[1]) if len(sys.argv) > 1 else 6
    cw.sh("root")
    cw.sh("wait-for-device")
    cw.connect()
    took = []
    for i in range(rounds):
        time.sleep(3.5)  # past any gap the watcher keeps between screen-offs
        before = cw.wallpaper_ids()["lock"]
        cw.sh("logcat", "-b", "events", "-c")
        cw.sh("shell", "input", "keyevent", "KEYCODE_SLEEP")
        deadline = time.time() + 15
        while cw.wallpaper_ids()["lock"] == before and time.time() < deadline:
            time.sleep(0.1)
        if cw.wallpaper_ids()["lock"] == before:
            print(f"round {i + 1}: the lock wallpaper did not change")
        else:
            ms = (lock_written_at() - screen_off_at()) * 1000
            took.append(ms)
            print(f"round {i + 1}: new lock wallpaper {ms:.0f} ms after the screen went off", flush=True)
        cw.d.screen_on()
        time.sleep(0.6)
        cw.sh("shell", "wm", "dismiss-keyguard")
    if took:
        print(f"median {statistics.median(took):.0f} ms, worst {max(took):.0f} ms over {len(took)} of {rounds} rounds")


if __name__ == "__main__":
    main()

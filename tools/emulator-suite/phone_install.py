"""Install a build on a real phone whenever it is plugged in, so nobody has to time the plug-in to the build.

With --build-log it waits for that log's last `BUILD_EXIT=N` line and installs only after a 0, and only the APK
that build wrote (one written after the log's last line came from a later build in the same tree). Without it,
it installs the APK as it is. It keeps a copy of the APK, waits for the phone, then runs `adb install -r`, which
keeps the app's data. It never uninstalls: the phone's albums live in the installed app's data, so a signature
mismatch stops here with the reason. An unplugged phone, a stuck adb or a refused install that can pass later
is retried until --hours (one budget for the build and the phone together) runs out. Every step prints one
timestamped line, and the run ends on one of INSTALLED, FAILED or GAVE UP.

    python -X utf8 tools/emulator-suite/phone_install.py --serial <serial> [--apk <apk>] [--build-log <log>] [--hours 48]
"""
import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time

import cw

APK = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "app", "build", "outputs", "apk", "release", "app-release.apk"))
# Refusals no retry fixes: the fix for a signature or version clash is an uninstall, which deletes the albums.
FINAL = ("INSTALL_FAILED_UPDATE_INCOMPATIBLE", "INSTALL_FAILED_VERSION_DOWNGRADE", "INSTALL_FAILED_INVALID_APK",
         "INSTALL_PARSE_FAILED", "INSTALL_FAILED_OLDER_SDK", "INSTALL_FAILED_NO_MATCHING_ABIS")


def say(line: str) -> None:
    print(f"{time.strftime('%Y-%m-%d %H:%M:%S')} {line}", flush=True)


def adb(*args: str, timeout: int) -> tuple[int, str]:
    """Runs adb; a call that hangs past its timeout reads as a failure, not an exception."""
    try:
        r = subprocess.run([cw.ADB, *args], capture_output=True, timeout=timeout, creationflags=cw.NO_WINDOW)
    except subprocess.TimeoutExpired:
        return -1, f"adb {' '.join(args[:3])} timed out after {timeout} s"
    return r.returncode, (r.stdout + r.stderr).decode("utf-8", "replace").strip()


def device_state(serial: str) -> str:
    """The phone's adb state (device, unauthorized, offline), absent, or why adb itself failed."""
    code, out = adb("devices", timeout=30)
    if code:
        return f"unreadable ({out[-160:]})"
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 2 and parts[0] == serial:
            return parts[1]
    return "absent"


def installed(serial: str) -> str:
    """The installed app's versionName and lastUpdateTime, or a note when they cannot be read."""
    _, out = adb("-s", serial, "shell", "dumpsys", "package", cw.PKG, timeout=60)
    found = re.findall(r"(versionName=\S+|lastUpdateTime=[\d\- :]+)", out)
    return " ".join(dict.fromkeys(s.strip() for s in found)) or "not installed or unreadable"


def wait_for_build(log: str, deadline: float) -> str:
    """Empty once the build in `log` finished with 0, else why not."""
    say(f"waiting for the build ({log})")
    while time.time() < deadline:
        try:
            with open(log, encoding="utf-8", errors="replace") as f:
                exits = re.findall(r"BUILD_EXIT=(\d+)", f.read())
        except OSError:
            exits = []
        if exits:
            return "" if exits[-1] == "0" else f"the build exited {exits[-1]}; nothing installed"
        time.sleep(5)
    return "GAVE UP: the build never finished"


def keep(apk: str, log: str | None) -> str:
    """A copy of the APK named by its content, so a later build in the tree cannot swap what gets installed."""
    if not os.path.isfile(apk):
        raise RuntimeError(f"no APK at {apk}")
    if log and os.path.getmtime(apk) > os.path.getmtime(log) + 1:
        raise RuntimeError(f"{apk} was written after {log} ended, by a later build; rebuild and run again")
    with open(apk, "rb") as f:
        digest = hashlib.sha256(f.read()).hexdigest()[:12]
    kept = os.path.join(tempfile.gettempdir(), f"connections-wallpaper-{digest}.apk")
    shutil.copy2(apk, kept)
    return kept


def install(serial: str, kept: str, deadline: float) -> int:
    told = ""
    while time.time() < deadline:
        state = device_state(serial)
        if state != "device":
            if state != told:
                say(f"phone {state}" + (": allow USB debugging on the phone" if state == "unauthorized" else ""))
                told = state
            time.sleep(10)
            continue
        say(f"phone attached; installed now: {installed(serial)}")
        code, out = adb("-s", serial, "install", "-r", kept, timeout=600)
        if code == 0 and "Success" in out:
            say(f"INSTALLED: {installed(serial)}")
            return 0
        if any(reason in out for reason in FINAL):
            say(f"FAILED: {out[-400:]}")
            return 1
        # Unplugged, offline, a stuck adb, or a refusal the phone can clear (a prompt, a restriction): try again.
        say(f"install did not finish ({out[-200:]}); trying again in 30 s")
        told = ""
        time.sleep(30)
    say("GAVE UP: the phone was not plugged in, or the install never went through")
    return 1


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--serial", required=True, help="the phone's adb serial; no other device is touched")
    p.add_argument("--apk", default=APK)
    p.add_argument("--build-log", help="wait for this log's last BUILD_EXIT=N line; a nonzero N installs nothing")
    p.add_argument("--hours", type=float, default=48, help="give up after this long, the build's wait included")
    a = p.parse_args()
    deadline = time.time() + a.hours * 3600
    kept = ""
    try:
        if a.build_log:
            why = wait_for_build(a.build_log, deadline)
            if why:
                say(why if why.startswith("GAVE UP") else f"FAILED: {why}")
                return 1
        kept = keep(a.apk, a.build_log)
        say(f"build ready, kept as {kept}; waiting for {a.serial}")
        return install(a.serial, kept, deadline)
    except Exception as e:  # noqa: BLE001 - an unattended run must still end on a line a watcher can read
        say(f"FAILED: {type(e).__name__}: {e}")
        return 1
    finally:
        if kept and os.path.isfile(kept):
            os.remove(kept)


if __name__ == "__main__":
    sys.exit(main())

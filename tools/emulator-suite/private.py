r"""Runs the suite on this PC's private test emulator, never on one another session is driving.

  python -X utf8 tools/emulator-suite/private.py [test ...]          (boot, install the release APK, run, stop)
  python -X utf8 tools/emulator-suite/private.py --keep [test ...]   (leave the emulator up; the next run reuses it)

emulator-5554 (AVD cwall) is shared by every session on this PC, and a second copy of one AVD does not start
("Running multiple emulators with the same AVD"). So the suite gets its own AVD, cwallci, made from cwall's config
on first use and booted headless on port 5570. Every step has a deadline: on 2026-10-20 a script waited 45 minutes
on an emulator that had exited at once. One run holds the AVD at a time; a second fails at once and names the
flags for another AVD.
"""
import argparse
import os
import subprocess
import sys
import time

import cw
import suite

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
APK = os.path.join(ROOT, "app", "build", "outputs", "apk", "release", "app-release.apk")


def adb(serial: str, *args: str, timeout: float = 30) -> str:
    try:
        r = subprocess.run([cw.ADB, "-s", serial, *args], capture_output=True, timeout=timeout, creationflags=cw.NO_WINDOW)
    except subprocess.TimeoutExpired:
        return ""
    return r.stdout.decode("utf-8", "replace").strip() if r.returncode == 0 else ""


def emulator_exe() -> str:
    for home in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"), os.path.dirname(os.path.dirname(cw.ADB))):
        for exe in ("emulator.exe", "emulator"):
            if home and os.path.isfile(os.path.join(home, "emulator", exe)):
                return os.path.join(home, "emulator", exe)
    sys.exit("no Android emulator found: set ANDROID_HOME to the SDK folder")


def avd_home() -> str:
    """Where the emulator looks for AVDs, in its own order."""
    if os.environ.get("ANDROID_AVD_HOME"):
        return os.environ["ANDROID_AVD_HOME"]
    user_home = os.environ.get("ANDROID_USER_HOME") or os.path.join(os.path.expanduser("~"), ".android")
    return os.path.join(user_home, "avd")


def ensure_avd(name: str, source: str) -> None:
    """Make AVD `name` from `source`'s config.ini; the emulator builds its disks from the system image on first boot."""
    home = avd_home()
    folder = os.path.join(home, f"{name}.avd")
    if os.path.isfile(os.path.join(home, f"{name}.ini")) and os.path.isfile(os.path.join(folder, "config.ini")):
        return
    src_ini = os.path.join(home, f"{source}.ini")
    src_cfg = os.path.join(home, f"{source}.avd", "config.ini")
    if not (os.path.isfile(src_ini) and os.path.isfile(src_cfg)):
        sys.exit(f"no AVD {source} to copy in {home}: make one in Android Studio's Device Manager first")
    os.makedirs(folder, exist_ok=True)
    with open(src_cfg, encoding="utf-8") as f:
        # Its name lines are replaced, and a line pointing into the source's own folder is dropped, so the copy never shares its disks.
        lines = [ln for ln in f.read().splitlines()
                 if not ln.replace(" ", "").startswith(("AvdId=", "avd.ini.displayname=")) and f"{source}.avd" not in ln]
    with open(os.path.join(folder, "config.ini"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join([f"AvdId={name}", f"avd.ini.displayname={name}", *lines]) + "\n")
    with open(src_ini, encoding="utf-8") as f:
        target = next((ln.split("=", 1)[1].strip() for ln in f if ln.startswith("target=")), "")
    with open(os.path.join(home, f"{name}.ini"), "w", encoding="utf-8", newline="\n") as f:
        f.write(f"avd.ini.encoding=UTF-8\npath={folder}\npath.rel=avd{os.sep}{name}.avd\ntarget={target}\n")
    print(f"made AVD {name} from {source}", flush=True)


def hold(name: str):
    """Hold AVD `name` for this run (released when the process ends), so two runs never drive or stop one emulator."""
    f = open(os.path.join(avd_home(), f"{name}.avd", "private-run.lock"), "a+")
    f.seek(0)
    try:
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(f.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        sys.exit(f"another run is using AVD {name}: wait for it, or pass --avd <new name> --port <free even port>")
    return f


def log_tail(path: str) -> str:
    with open(path, encoding="utf-8", errors="replace") as f:
        return "".join(f.readlines()[-12:])


def start(name: str, port: int) -> tuple[subprocess.Popen, str]:
    log = os.path.join(HERE, "shots", f"{name}-{port}-emulator.log")
    os.makedirs(os.path.dirname(log), exist_ok=True)
    # Public resolvers, not the host's: the emulator takes every host adapter's DNS, a VPN's tunnel-only one included,
    # and lost DNS mid-run when that tunnel dropped, failing every gallery flow after it (2026-10-22).
    cmd = [emulator_exe(), "-avd", name, "-port", str(port), "-no-window", "-no-audio", "-no-boot-anim",
           "-gpu", "swiftshader_indirect", "-no-snapshot", "-memory", "3072", "-dns-server", "1.1.1.1,8.8.8.8"]
    with open(log, "w", encoding="utf-8") as out:
        return subprocess.Popen(cmd, stdout=out, stderr=subprocess.STDOUT, creationflags=cw.NO_WINDOW), log


def stop(serial: str, proc: subprocess.Popen) -> None:
    """Stop an emulator this run started. emulator.exe is a launcher: the VM is its child, so a forced stop takes the tree."""
    adb(serial, "emu", "kill", timeout=15)
    try:
        proc.wait(timeout=60)
    except subprocess.TimeoutExpired:
        if os.name == "nt":
            subprocess.run(["taskkill", "/T", "/F", "/PID", str(proc.pid)], capture_output=True, creationflags=cw.NO_WINDOW)
        else:
            proc.kill()
        proc.wait(timeout=30)


def wait_booted(serial: str, name: str, timeout: float, proc: subprocess.Popen | None = None, log: str = "") -> None:
    started = time.time()
    while adb(serial, "shell", "getprop", "sys.boot_completed", timeout=10) != "1":
        if proc and proc.poll() is not None:
            sys.exit(f"emulator {name} exited during boot (code {proc.returncode}); its log, {log}, ends:\n{log_tail(log)}")
        if time.time() - started > timeout:
            sys.exit(f"{serial} ({name}) did not boot in {timeout:.0f}s" + (f"; its log, {log}, ends:\n{log_tail(log)}" if log else ""))
        time.sleep(3)
    print(f"{serial} ({name}) booted in {time.time() - started:.0f}s", flush=True)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("tests", nargs="*", help="suite test names; none runs every test")
    p.add_argument("--avd", default="cwallci", help="the private AVD (default cwallci)")
    p.add_argument("--from-avd", default="cwall", help="the AVD whose config makes it on first use (default cwall)")
    p.add_argument("--port", type=int, default=5570, help="console port, even; the serial is emulator-<port> (default 5570)")
    p.add_argument("--boot-timeout", type=float, default=300, help="seconds a boot may take (default 300)")
    p.add_argument("--suite-timeout", type=float, default=1800, help="seconds the suite may take (default 1800)")
    p.add_argument("--keep", action="store_true", help="leave the emulator running after the suite")
    p.add_argument("--apk", default=APK, help="the APK to install (default: this tree's release build)")
    a = p.parse_args()
    unknown = suite.unknown_tests(a.tests)
    if unknown:
        sys.exit(f"no such test: {', '.join(unknown)}")
    if not os.path.isfile(a.apk):
        sys.exit(f"no release APK at {a.apk}: run gradlew assembleRelease (or tools\\emulator-suite\\check.cmd) first")

    ensure_avd(a.avd, a.from_avd)
    held = hold(a.avd)  # noqa: F841 - the lock lasts as long as this handle
    serial = f"emulator-{a.port}"
    running = adb(serial, "emu", "avd", "name", timeout=10).splitlines()
    if running and running[0].strip() != a.avd:
        sys.exit(f"{serial} is AVD {running[0].strip()}, not {a.avd}: pass another --port")

    proc, ready, code = None, False, 1
    try:
        if running:
            print(f"{serial} ({a.avd}) is already up; reusing it", flush=True)
            wait_booted(serial, a.avd, a.boot_timeout)
        else:
            proc, log = start(a.avd, a.port)
            wait_booted(serial, a.avd, a.boot_timeout, proc, log)
        ready = True
        try:
            installed = subprocess.run([cw.ADB, "-s", serial, "install", "-r", a.apk], capture_output=True, timeout=300,
                                       creationflags=cw.NO_WINDOW)
        except subprocess.TimeoutExpired:
            sys.exit("install took over 300s")
        if installed.returncode:
            sys.exit(f"install failed: {(installed.stdout + installed.stderr).decode('utf-8', 'replace').strip()[-400:]}")
        try:
            code = subprocess.run([sys.executable, "-X", "utf8", os.path.join(HERE, "suite.py"), *a.tests],
                                  env={**os.environ, "ANDROID_SERIAL": serial}, timeout=a.suite_timeout).returncode
        except subprocess.TimeoutExpired:
            print(f"the suite took over {a.suite_timeout:.0f}s and was stopped", flush=True)
    finally:
        # A boot that failed is always stopped; one that came up stays only with --keep.
        if proc and (not a.keep or not ready):
            stop(serial, proc)
    sys.exit(code)


if __name__ == "__main__":
    main()

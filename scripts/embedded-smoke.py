#!/usr/bin/env python3
"""Smoke-test the deployed Compose embedded app on the device: restart it and watch it come all the way up.

usage: embedded-smoke.py [--host DEST] [--build release|debug] [--timeout SECONDS] [--hold SECONDS] [--no-restart]

Run after scripts/deploy-pi.sh. The app renders to DRM, so there is no screen to read: the check is the journal of
one bitchat.service invocation. The script restarts the unit (sudo -n systemctl restart, the same rule deploy-pi.sh
uses; --no-restart checks the invocation that is already running) and then requires, within --timeout seconds:

  * the identity line from /opt/bitchat/releases/current/BUILD_INFO (identity=...), exactly;
  * "[Renderer] Skia DirectContext created" (DRM, GBM, EGL and Skia are up);
  * "[Main] Entering event-driven loop" (startup finished: input devices, Compose scene, first frame);

and after the last of those, --hold more seconds with the same InvocationID and the unit active. At no point may the
invocation's journal contain "Uncaught Kotlin exception", and the unit must never leave active/activating. deploy-pi.sh
only confirms the unit for about 4 s after the identity line, which a crash later in startup slips past: the release
binary of c199524 died after "[Main] Touch input ready", well after that window (KT-88544, see gradle.properties).

--build requires BUILD_INFO's build= value (for example release, before trusting a release deploy).
The target is PI_HOST (an ssh destination, as for deploy-pi.sh) or --host; key-based ssh must work (BatchMode).
Exit status is 0 when every check passed. python3 stdlib only.
"""
import argparse
import os
import re
import subprocess
import sys
import time

SERVICE = "bitchat.service"
BUILD_INFO = "/opt/bitchat/releases/current/BUILD_INFO"
RENDERER_MARKER = "[Renderer] Skia DirectContext created"
LOOP_MARKER = "[Main] Entering event-driven loop"
CRASH_MARKER = "Uncaught Kotlin exception"
POLL_INTERVAL = 2.0
SSH_OPTS = ["-o", "BatchMode=yes", "-o", "ConnectTimeout=60"]
HEX_RE = re.compile(r"^[0-9a-f]+$")

failures = []


def report(ok, what, detail=""):
    print(f"{'PASS' if ok else 'FAIL'}: {what}" + (f" ({detail})" if detail else ""))
    if not ok:
        failures.append(what)


def info(text):
    print(f"INFO: {text}")


def parse_build_info(text):
    """BUILD_INFO's key=value lines as a dict (the first '=' splits)."""
    values = {}
    for line in text.splitlines():
        key, sep, value = line.partition("=")
        if sep and key:
            values[key] = value
    return values


def parse_poll(output):
    """The poll command's output: state=, invocation=, then the journal after a __journal__ line."""
    head, sep, journal = output.partition("__journal__\n")
    if not sep:
        raise ValueError(f"unexpected poll output: {output[:200]!r}")
    fields = parse_build_info(head)
    return fields.get("state", ""), fields.get("invocation", ""), journal


def evaluate(identity, invocation, state, now_invocation, journal):
    """One poll's verdict: ("fail", reason), ("up", None) once every startup marker is in, else ("starting", None)."""
    lines = journal.splitlines()
    if any(CRASH_MARKER in line for line in lines):
        return "fail", f"the journal of invocation {invocation} has {CRASH_MARKER!r}"
    if now_invocation != invocation:
        return "fail", (f"invocation {invocation} ended or was replaced (InvocationID now {now_invocation or 'empty'!r}, "
                        f"state {state!r}): the app exited or systemd restarted it")
    if state not in ("active", "activating"):
        return "fail", f"{SERVICE} is {state!r}"
    if identity in lines and any(RENDERER_MARKER in line for line in lines) and any(LOOP_MARKER in line for line in lines):
        return "up", None
    return "starting", None


def missing_markers(identity, journal):
    lines = journal.splitlines()
    missing = []
    if identity not in lines:
        missing.append(f"identity line {identity!r}")
    for marker in (RENDERER_MARKER, LOOP_MARKER):
        if not any(marker in line for line in lines):
            missing.append(repr(marker))
    return missing


class Device:
    def __init__(self, host):
        self.host = host

    def run(self, command):
        """Run `command` on the device (as the ssh argument, never via stdin); return (exit code, stdout)."""
        result = subprocess.run(["ssh", "-n", *SSH_OPTS, self.host, command], text=True, capture_output=True)
        if result.returncode == 255:
            raise SystemExit(f"embedded-smoke: ssh transport failure talking to {self.host}: {result.stderr.strip()}")
        return result.returncode, result.stdout

    def invocation_id(self):
        code, out = self.run(f"systemctl show -p InvocationID --value {SERVICE}")
        return out.strip() if code == 0 else ""

    def poll(self, invocation):
        # invocation was checked to be hex before it is spliced in.
        command = (f"echo state=$(systemctl is-active {SERVICE}); "
                   f"echo invocation=$(systemctl show -p InvocationID --value {SERVICE}); "
                   f"echo __journal__; journalctl -q _SYSTEMD_INVOCATION_ID={invocation} --no-pager -o cat")
        code, out = self.run(command)
        if code != 0:
            raise SystemExit(f"embedded-smoke: polling {SERVICE} failed on {self.host} (exit {code})")
        return parse_poll(out)


def main():
    parser = argparse.ArgumentParser(
        usage="embedded-smoke.py [--host DEST] [--build release|debug] [--timeout SECONDS] [--hold SECONDS] [--no-restart]",
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default=os.environ.get("PI_HOST"), help="ssh destination (default: $PI_HOST)")
    parser.add_argument("--build", choices=["release", "debug"], help="require this build= in BUILD_INFO")
    parser.add_argument("--timeout", type=float, default=120.0, metavar="SECONDS",
                        help="time for every startup marker to appear (default 120: the unit waits up to 20 s for input devices)")
    parser.add_argument("--hold", type=float, default=30.0, metavar="SECONDS",
                        help="how long the app must then stay up (default 30)")
    parser.add_argument("--no-restart", action="store_true", help=f"check the running invocation instead of restarting {SERVICE}")
    args = parser.parse_args()
    if not args.host:
        parser.error("no target; set PI_HOST=user@host or pass --host")

    device = Device(args.host)
    code, text = device.run(f"cat {BUILD_INFO}")
    build_info = parse_build_info(text) if code == 0 else {}
    identity = build_info.get("identity", "")
    if not identity:
        raise SystemExit(f"embedded-smoke: no identity= line in {BUILD_INFO} on {args.host} (exit {code}); deploy with scripts/deploy-pi.sh first")
    info(f"current release: {build_info.get('release', '?')}")
    info(f"identity: {identity}")
    if args.build:
        report(build_info.get("build") == args.build, f"BUILD_INFO says build={args.build}", f"got {build_info.get('build')!r}")

    if not args.no_restart:
        code, _ = device.run(f"sudo -n systemctl restart {SERVICE}")
        if code != 0:
            raise SystemExit(f"embedded-smoke: sudo -n systemctl restart {SERVICE} failed on {args.host} (exit {code})")
        info(f"restarted {SERVICE}")
    invocation = device.invocation_id()
    if not HEX_RE.match(invocation):
        raise SystemExit(f"embedded-smoke: {SERVICE} has no InvocationID (got {invocation!r}); check: sudo -n systemctl status {SERVICE}")
    info(f"watching invocation {invocation}")

    journal = ""
    up_at = None
    start = time.monotonic()
    verdict, reason = "starting", None
    while True:
        state, now_invocation, journal = device.poll(invocation)
        verdict, reason = evaluate(identity, invocation, state, now_invocation, journal)
        elapsed = time.monotonic() - start
        if verdict == "fail":
            break
        if verdict == "up" and up_at is None:
            up_at = elapsed
            info(f"all startup markers in after {elapsed:.0f} s; holding {args.hold:.0f} s")
        if up_at is not None and elapsed - up_at >= args.hold:
            break
        if up_at is None and elapsed >= args.timeout:
            verdict, reason = "fail", "not up within %.0f s, missing: %s" % (args.timeout, ", ".join(missing_markers(identity, journal)))
            break
        time.sleep(POLL_INTERVAL)

    if up_at is None:
        report(False, "identity, Renderer and event-loop lines appeared", reason or "")
    else:
        report(True, "identity, Renderer and event-loop lines appeared", f"after {up_at:.0f} s")
        report(verdict != "fail", f"stayed up {args.hold:.0f} s after startup, no {CRASH_MARKER!r}", reason or "")
    if failures:
        print(f"--- journal of invocation {invocation} (last 40 lines) ---")
        print("\n".join(journal.splitlines()[-40:]))
    print("RESULT: " + ("FAIL (" + str(len(failures)) + " check(s))" if failures else "PASS"))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Smoke-test a terminal UI in a pty: render, stop, and check the terminal is given back intact.

usage: tui-smoke.py [--signal TERM|HUP | --hangup | --expect-exit N] [--expect TEXT]... [--forbid TEXT]...
                    [--cycle NAME=MARKER]... [--hold SECONDS] [--exit-code N] [--dump FILE]
                    [--log FILE] [--log-expect TEXT]... -- CMD ARGS

The command runs on a new 120x40 pty with TERM=xterm-256color, as session leader with the pty as its
controlling terminal (Mosaic opens /dev/tty, not stdout). Each --expect text must show up within 30 s.
After --hold seconds the script sends Ctrl+C (or the signal, for --signal), waits up to 10 s for the
child to exit (SIGKILL and FAIL otherwise) and then checks that

  * the output restores the terminal after the last frame: leaves the alternate screen (CSI ?1049l)
    and shows the cursor (CSI ?25h);
  * the pty's complete termios state (all four flag words, both speeds and every control character) is what it
    was before the child started; differing fields are printed on failure;
  * no --forbid text ever appeared in the pty stream (e.g. log lines that must go to a file).

--cycle NAME=MARKER (repeatable, in screen order) walks the screens once the --expect texts are in: the first entry
is checked on the screen that is already showing, every later entry is checked after sending Tab, and each MARKER
must then appear in what the terminal received since that Tab (within 10 s). For the bitchat TUI:
  --cycle chat="Enter send" --cycle peers="People (" --cycle locations="Locations" --cycle settings="Settings"
Markers are the screen titles: the terminal only receives the cells that changed, so a marker that shares
letters with the previous screen's text in the same place (a footer hint such as "Enter join" after
"Enter DM") can arrive split up; titles of neighbouring screens share none.

--expect-exit N is for a command that must end by itself (for example a second instance refusing to start):
nothing is sent, the command must exit with code N within 10 s, and only the termios restore is checked (it never
entered the alternate screen). It excludes --signal, --hangup, --cycle, --hold and --exit-code.

--log FILE checks that the text the command appended to FILE during this run holds the app startup line;
each --log-expect TEXT must be in that text as well (for example native stderr that the app sends to the log).

--hangup stops the command the way closing a terminal window does: the pty master is closed and SIGHUP goes
to the command's process group. It must exit within 10 s (any exit code: a TUI may leave on the SIGHUP or on
reading end-of-file from the dead tty first, so this variant alone does not prove the SIGHUP handler; use
--signal HUP for that); the termios and escape-sequence checks are skipped because the terminal is gone (the
--forbid and --log checks still apply).

The pty has no terminal emulator behind it: the script answers the two queries Mosaic waits on
(device attributes and device status), which is all a real terminal needs to do for startup.
Exit status is 0 when every check passed. The whole run is capped at 120 s. python3 stdlib only.
"""
import argparse
import fcntl
import os
import re
import select
import signal
import struct
import sys
import termios
import time

ROWS, COLS = 40, 120
GLOBAL_TIMEOUT = 120.0
EXPECT_TIMEOUT = 30.0
EXIT_TIMEOUT = 10.0

CSI_RE = re.compile(rb"\x1b\[[0-9;:?<=>!$ ]*[@-~]|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)|\x1b[()][A-Z0-9]|\x1b[=>78]")
DA1_QUERY = b"\x1b[0c"
DSR_QUERY = b"\x1b[5n"
DA1_REPLY = b"\x1b[?62;c"
DSR_REPLY = b"\x1b[0n"
ALT_ON, ALT_OFF = b"\x1b[?1049h", b"\x1b[?1049l"
CURSOR_HIDE, CURSOR_SHOW = b"\x1b[?25l", b"\x1b[?25h"

failures = []


def report(ok, what, detail=""):
    print(f"{'PASS' if ok else 'FAIL'}: {what}" + (f" ({detail})" if detail else ""))
    if not ok:
        failures.append(what)


def info(text):
    print(f"INFO: {text}")


class Session:
    """The command on a pty, under a small session-leader process.

    The leader (setsid, pty as controlling terminal) forks the command and stays alive after it exits,
    because macOS revokes the pty from every holder once its session leader is gone, which would make
    the termios read-back after exit impossible. The leader reports the command's pid and wait status
    over a pipe; signals go straight to the command.
    """

    def __init__(self, argv, deadline):
        self.deadline = deadline
        self.out = bytearray()
        self.answered = {DA1_QUERY: 0, DSR_QUERY: 0}
        self.master, self.slave = os.openpty()
        fcntl.ioctl(self.slave, termios.TIOCSWINSZ, struct.pack("HHHH", ROWS, COLS, 0, 0))
        # Taken before the command starts; read back from the same slave afterwards.
        self.saved_termios = termios.tcgetattr(self.slave)
        self.eof = False
        self.status = None
        self.ctl = b""
        self.ctl_r, ctl_w = os.pipe()
        self.leader = os.fork()
        if self.leader == 0:
            code = 127
            try:
                os.close(self.ctl_r)
                os.setsid()
                # A hangup goes to the whole group, leader included; the leader must live on to report the status.
                # A caught signal is reset to its default when the command execs, so the command is unaffected.
                signal.signal(signal.SIGHUP, lambda *_: None)
                fcntl.ioctl(self.slave, termios.TIOCSCTTY, 0)
                child = os.fork()
                if child == 0:
                    try:
                        for fd in (0, 1, 2):
                            os.dup2(self.slave, fd)
                        for fd in (self.master, self.slave, ctl_w):
                            os.close(fd)
                        os.execvpe(argv[0], argv, dict(os.environ, TERM="xterm-256color"))
                    except BaseException as exc:
                        os.write(2, f"tui-smoke: exec failed: {exc}\n".encode())
                    finally:
                        os._exit(127)
                # The leader keeps neither end of the pty open, so closing the parent's master really hangs the
                # terminal up: only the command (and its children) still hold the slave, as with a terminal window.
                os.close(self.master)
                os.close(self.slave)
                os.write(ctl_w, f"{child}\n".encode())
                _, status = os.waitpid(child, 0)
                os.write(ctl_w, f"{status}\n".encode())
                # Stay the session leader until the parent kills this process, or goes away (then so do we).
                parent = os.getppid()
                stay_until = time.monotonic() + GLOBAL_TIMEOUT + 30
                while os.getppid() == parent and time.monotonic() < stay_until:
                    time.sleep(0.5)
                code = 0
            finally:
                os._exit(code)
        os.close(ctl_w)
        first = self.read_ctl_line(10.0)
        if first is None:
            self.kill()
            raise SystemExit("tui-smoke: the session leader did not report the command's pid")
        self.pid = int(first)

    def read_ctl_line(self, timeout):
        """The next line from the leader, or None if there is none within `timeout`."""
        end = time.monotonic() + timeout
        while b"\n" not in self.ctl:
            ready, _, _ = select.select([self.ctl_r], [], [], max(end - time.monotonic(), 0))
            if not ready:
                return None
            data = os.read(self.ctl_r, 100)
            if not data:
                return None
            self.ctl += data
        line, self.ctl = self.ctl.split(b"\n", 1)
        return line.decode()

    def time_left(self):
        return self.deadline - time.monotonic()

    def pump(self, seconds):
        """Read and answer queries for up to `seconds` (bounded by the global deadline); return early once the child is gone."""
        end = time.monotonic() + min(seconds, max(self.time_left(), 0))
        while True:
            left = end - time.monotonic()
            if left <= 0:
                return
            if not self.eof:
                ready, _, _ = select.select([self.master], [], [], min(left, 0.1))
                if ready:
                    try:
                        data = os.read(self.master, 65536)
                    except OSError:  # macOS: EIO once the session leader has exited, though the slave is still open here
                        data = b""
                    if data:
                        self.out += data
                        self.answer_queries()
                        continue
                    self.eof = True
            else:
                time.sleep(min(left, 0.05))
            if self.poll_exit():
                return

    def answer_queries(self):
        for query, reply in ((DA1_QUERY, DA1_REPLY), (DSR_QUERY, DSR_REPLY)):
            seen = self.out.count(query)
            while self.answered[query] < seen:
                os.write(self.master, reply)
                self.answered[query] += 1

    def poll_exit(self):
        if self.status is None:
            line = self.read_ctl_line(0)
            if line is not None:
                self.status = int(line)
        return self.status is not None

    def exit_code(self):
        if os.WIFEXITED(self.status):
            return os.WEXITSTATUS(self.status)
        return 128 + os.WTERMSIG(self.status)

    def text(self):
        return bytes(self.out)

    def stripped(self, since=0):
        return CSI_RE.sub(b"", bytes(self.out[since:]))

    def seen(self, needle, since=0):
        """Whether `needle` is in the output received from byte offset `since` on (raw, or with escape sequences removed)."""
        data = needle.encode()
        return data in self.out[since:] or data in self.stripped(since)

    def hang_up(self):
        """Close the pty master, as a terminal window closing does, and send SIGHUP to the command's process group."""
        if self.master >= 0:
            os.close(self.master)
            self.master = -1
        os.close(self.slave)  # no terminal is left: nobody but the command may hold the pty
        self.slave = -1
        self.eof = True
        os.killpg(self.leader, signal.SIGHUP)  # the leader is the group leader; it catches SIGHUP and stays

    def kill(self):
        """SIGKILL the whole session (the leader's pgid is its pid, and the command shares it) and reap the leader."""
        try:
            os.killpg(self.leader, signal.SIGKILL)
        except OSError:
            pass
        try:
            os.waitpid(self.leader, 0)
        except OSError:
            pass


def restore_checks(session):
    out = session.text()
    last_alt_on = out.rfind(ALT_ON)
    last_alt_off = out.rfind(ALT_OFF)
    report(last_alt_on >= 0, "entered the alternate screen (CSI ?1049h)")
    report(last_alt_off > last_alt_on, "left the alternate screen after the last frame (CSI ?1049l)")
    last_hide = out.rfind(CURSOR_HIDE)
    last_show = out.rfind(CURSOR_SHOW)
    report(last_show >= 0 and last_show > last_hide, "cursor shown after the last frame (CSI ?25h)")
    trailing = CSI_RE.sub(b"", out[last_alt_off + len(ALT_OFF):]) if last_alt_off >= 0 else b""
    report(not trailing.strip(), "no frame text after leaving the alternate screen", repr(trailing[:60]) if trailing.strip() else "")

    termios_check(session)


# Names of the bits in each termios flag word and of the control-character slots, for readable differences.
FLAG_BITS = {
    "iflag": ("IGNBRK BRKINT IGNPAR PARMRK INPCK ISTRIP INLCR IGNCR ICRNL IXON IXOFF IXANY IMAXBEL IUTF8 IUCLC"),
    "oflag": ("OPOST ONLCR OCRNL ONOCR ONLRET OFILL OFDEL OXTABS ONOEOT OLCUC NLDLY CRDLY TABDLY BSDLY VTDLY FFDLY"),
    "cflag": ("CS5 CS6 CS7 CS8 CSTOPB CREAD PARENB PARODD HUPCL CLOCAL CCTS_OFLOW CRTS_IFLOW CDTR_IFLOW CDSR_OFLOW CCAR_OFLOW"),
    "lflag": ("ECHOKE ECHOE ECHOK ECHO ECHONL ECHOPRT ECHOCTL ISIG ICANON ALTWERASE IEXTEN EXTPROC TOSTOP FLUSHO NOKERNINFO "
              "PENDIN NOFLSH XCASE"),
}
CC_NAMES = ("VEOF VEOL VEOL2 VERASE VWERASE VKILL VREPRINT VINTR VQUIT VSUSP VDSUSP VSTART VSTOP VLNEXT VDISCARD VMIN VTIME "
            "VSTATUS VSWTC").split()


def bit_names(word, changed):
    names = [name for name in FLAG_BITS[word].split() if getattr(termios, name, 0) & changed]
    return "/".join(names) if names else "unnamed bits"


def termios_differences(before, after):
    """Every field of two tcgetattr() results that differs: the four flag words, both speeds and all control characters."""
    diffs = []
    for index, word in enumerate(("iflag", "oflag", "cflag", "lflag")):
        if before[index] != after[index]:
            changed = before[index] ^ after[index]
            diffs.append(f"{word} {before[index]:#x}->{after[index]:#x} (changed {changed:#x}: {bit_names(word, changed)})")
    for index, word in ((4, "ispeed"), (5, "ospeed")):
        if before[index] != after[index]:
            diffs.append(f"{word} {before[index]}->{after[index]}")
    names = {getattr(termios, name): name for name in CC_NAMES if hasattr(termios, name)}
    before_cc, after_cc = list(before[6]), list(after[6])
    if len(before_cc) != len(after_cc):
        diffs.append(f"cc length {len(before_cc)}->{len(after_cc)}")
    for slot, (old, new) in enumerate(zip(before_cc, after_cc)):
        if old != new:
            diffs.append(f"cc[{slot}] {names.get(slot, 'slot ' + str(slot))} {old!r}->{new!r}")
    return diffs


def termios_check(session):
    after = termios.tcgetattr(session.slave)
    before = session.saved_termios
    core = {"ECHO": (3, termios.ECHO), "ICANON": (3, termios.ICANON), "ISIG": (3, termios.ISIG)}
    bad = [name for name, (idx, bit) in core.items() if (before[idx] & bit) != (after[idx] & bit)]
    report(not bad, "termios ECHO/ICANON/ISIG restored", ",".join(bad))
    diffs = termios_differences(before, after)
    report(not diffs, "termios state fully restored (flag words, speeds and all control characters)", "; ".join(diffs))


def walk_screens(session, cycles, deadline):
    """Check each (name, marker): the first on the current screen, every later one after a Tab."""
    for index, (name, marker) in enumerate(cycles):
        since = 0 if index == 0 else len(session.out)
        if index > 0 and session.status is None:
            os.write(session.master, b"\t")
        end = min(time.monotonic() + EXPECT_TIMEOUT / 3, deadline)
        while not session.seen(marker, since) and time.monotonic() < end and session.status is None:
            session.pump(0.2)
        ok = session.seen(marker, since)
        report(ok, f"screen {name!r} showed {marker!r}" + ("" if index == 0 else " after Tab"),
               "child exited early" if session.status is not None and not ok else "")


def main():
    parser = argparse.ArgumentParser(add_help=True, usage="tui-smoke.py [options] -- CMD ARGS", description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--signal", choices=["TERM", "HUP"], help="send this signal instead of Ctrl+C")
    parser.add_argument("--hangup", action="store_true",
                        help="close the pty master and send SIGHUP to the process group, like closing the terminal window")
    parser.add_argument("--expect", action="append", default=[], metavar="TEXT", help="text that must appear (repeatable)")
    parser.add_argument("--forbid", action="append", default=[], metavar="TEXT", help="text that must never appear (repeatable)")
    parser.add_argument("--hold", type=float, default=0.0, metavar="SECONDS", help="keep running this long before stopping")
    parser.add_argument("--exit-code", type=int, default=None, metavar="N", help="also require this exit code")
    parser.add_argument("--dump", metavar="FILE", help="write the raw pty stream here")
    parser.add_argument("--log", metavar="FILE", help="a text log file that must contain the app startup line")
    parser.add_argument("--log-expect", action="append", default=[], metavar="TEXT",
                        help="text that must be in what this run appended to the --log file (repeatable)")
    parser.add_argument("--cycle", action="append", default=[], metavar="NAME=MARKER",
                        help="screen walk: first entry on the current screen, later ones after Tab (repeatable)")
    parser.add_argument("--expect-exit", type=int, default=None, metavar="N",
                        help="the command must exit by itself with this code; nothing is sent to it")
    argv = sys.argv[1:]
    if "--" not in argv:
        parser.error("missing -- CMD ARGS")
    split = argv.index("--")
    args = parser.parse_args(argv[:split])
    cmd = argv[split + 1:]
    if args.hangup and args.signal:
        parser.error("--hangup and --signal are mutually exclusive")
    if args.expect_exit is not None and (args.hangup or args.signal or args.cycle or args.hold or args.exit_code is not None):
        parser.error("--expect-exit excludes --signal, --hangup, --cycle, --hold and --exit-code")
    if args.log_expect and not args.log:
        parser.error("--log-expect needs --log")
    cycles = []
    for entry in args.cycle:
        name, sep, marker = entry.partition("=")
        if not sep or not name or not marker:
            parser.error(f"--cycle needs NAME=MARKER, got {entry!r}")
        cycles.append((name, marker))
    if not cmd:
        parser.error("missing CMD after --")

    log_start = 0
    if args.log:
        try:
            log_start = os.path.getsize(args.log)
        except OSError:
            pass
    deadline = time.monotonic() + GLOBAL_TIMEOUT
    # A caller that gives up on this script (a test timeout) sends SIGTERM: leave through the `finally` below so the
    # command's session is killed, rather than leaving it behind.
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    session = Session(cmd, deadline)
    try:
        info(f"started pid {session.pid}: {' '.join(cmd)} on a {COLS}x{ROWS} pty")
        for text in args.expect:
            end = min(time.monotonic() + EXPECT_TIMEOUT, deadline)
            while not session.seen(text) and time.monotonic() < end and session.status is None:
                session.pump(0.2)
            report(session.seen(text), f"expected text appeared: {text!r}",
                   "child exited early" if session.status is not None and not session.seen(text) else "")
        if cycles:
            walk_screens(session, cycles, deadline)
        if args.expect_exit is not None:
            end = min(time.monotonic() + EXIT_TIMEOUT, deadline)
            while session.status is None and time.monotonic() < end:
                session.pump(0.2)
            if session.status is None:
                report(False, f"exited by itself within {EXIT_TIMEOUT:.0f} s", "still running, killed with SIGKILL")
                session.kill()
            else:
                code = session.exit_code()
                report(code == args.expect_exit,
                       f"exited by itself with code {args.expect_exit} within {EXIT_TIMEOUT:.0f} s",
                       "" if code == args.expect_exit else f"got {code}")
        else:
            if session.status is None:
                session.pump(args.hold)
            if session.status is not None:
                report(False, "child still running when the stop was due", f"exit code {session.exit_code()}")
            else:
                if args.hangup:
                    session.hang_up()
                    info("closed the pty master and sent SIGHUP to the process group")
                elif args.signal:
                    os.kill(session.pid, getattr(signal, "SIG" + args.signal))
                    info(f"sent SIG{args.signal}")
                else:
                    os.write(session.master, b"\x03")
                    info("sent Ctrl+C")
                end = min(time.monotonic() + EXIT_TIMEOUT, deadline)
                while session.status is None and time.monotonic() < end:
                    session.pump(0.2)
                if session.status is None:
                    report(False, f"exited within {EXIT_TIMEOUT:.0f} s", "killed with SIGKILL")
                    session.kill()
                else:
                    report(True, f"exited within {EXIT_TIMEOUT:.0f} s", f"exit code {session.exit_code()}")
                    if args.exit_code is not None:
                        report(session.exit_code() == args.exit_code, f"exit code is {args.exit_code}",
                               f"got {session.exit_code()}")
        session.pump(0.5)  # whatever the child left in the pty buffer
        if session.time_left() <= 0:
            report(False, f"finished within the {GLOBAL_TIMEOUT:.0f} s global timeout")
        if args.hangup:
            info("hangup mode: termios and escape-sequence checks skipped, the terminal is gone")
            if session.status is not None:
                try:
                    os.kill(session.pid, 0)
                    report(False, "process is gone after the hangup", f"pid {session.pid} still exists")
                except ProcessLookupError:
                    report(True, "process is gone after the hangup", f"pid {session.pid}")
                except PermissionError:
                    report(False, "process is gone after the hangup", f"pid {session.pid} belongs to someone else now")
        elif args.expect_exit is not None:
            if session.status is not None:
                termios_check(session)
        elif session.status is not None:
            restore_checks(session)
        for text in args.forbid:
            report(not session.seen(text), f"never appeared in the pty stream: {text!r}")
        if args.log:
            try:
                with open(args.log, "rb") as handle:
                    size = os.fstat(handle.fileno()).st_size
                    handle.seek(log_start if size >= log_start else 0)  # only what this run wrote (or the whole file after a rotation)
                    log = handle.read().decode("utf-8", errors="replace")
                report("=== bitchat TUI ===" in log, "app startup line appeared in log (written by this run)")
                for text in args.log_expect:
                    report(text in log, f"appeared in the log file (written by this run): {text!r}")
            except OSError as error:
                report(False, f"could not read log file: {args.log}", str(error))
    finally:
        session.kill()
        if args.dump:
            with open(args.dump, "wb") as handle:
                handle.write(session.text())
        if session.master >= 0:
            os.close(session.master)
        if session.slave >= 0:
            os.close(session.slave)
        os.close(session.ctl_r)
    print("RESULT: " + ("FAIL (" + str(len(failures)) + " check(s))" if failures else "PASS"))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())

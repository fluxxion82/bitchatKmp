#!/usr/bin/env bash
#
# Report whether the Pi's outbound BLE is healthy over a window of its journal.
#
# The embedded build's outbound BLE works and then, after some hours, stops: connect attempts reach
# `Connected: true` and lose the link immediately, and every attempt fails. It survives an app restart,
# so it is not in our process. See the untracked docs/plans/2026-09-08-pi-outbound-bluetooth.md.
#
# The tell is in the kernel log rather than ours. `Opcode 0x2036/0x2039 failed: -16` -- LE Set Extended
# Advertising Parameters and Enable, refused as Command Disallowed -- appears about once per outbound
# connect request while degraded and not at all while healthy. That ratio is what the verdict is built
# on; the rest is context for reading it.
#
# Usage:
#   scripts/ble-outbound-report.sh [--since WHEN] [--until WHEN] [--host DEST]
#   scripts/ble-outbound-report.sh --app-log FILE --kernel-log FILE
#
# The device is not baked into this repository: the first form needs PI_HOST (an ssh
# destination -- user@host, or a Host alias from ~/.ssh/config) or --host.
#
# WHEN is anything journalctl accepts: "-6h", "2026-09-08 05:12", "today".
#
# The second form counts saved journal text instead of reaching for the device, which is how the
# verdict logic is tested (the untracked tests/ble-outbound-report/) and how a window that journald has since
# vacuumed can still be reported on. Save one with:
#   ssh HOST 'journalctl -u bitchat.service --since ... --no-pager' > app.log
#   ssh HOST 'journalctl -k --since ... --no-pager' > kernel.log

set -uo pipefail

SINCE="-1h"
UNTIL="now"
HOST="${PI_HOST:-}"
APP_LOG=""
KERNEL_LOG=""

while [ $# -gt 0 ]; do
  case "$1" in
    --since)      SINCE="${2:?--since needs a value}";      shift 2 ;;
    --until)      UNTIL="${2:?--until needs a value}";       shift 2 ;;
    --host)       HOST="${2:?--host needs a value}";         shift 2 ;;
    --app-log)    APP_LOG="${2:?--app-log needs a path}";    shift 2 ;;
    --kernel-log) KERNEL_LOG="${2:?--kernel-log needs a path}"; shift 2 ;;
    -h|--help)    sed -n '2,25p' "$0" | sed 's/^# \?//'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

# Every line the report cares about, so a remote fetch can be filtered down to a few kilobytes before
# it crosses the network while the counting still happens in exactly one place, below.
APP_INTEREST='Connect request:|BLUEZ_CLIENT\] INFO: Connected to|Failed to initiate connection|le-connection-abort-by-local|domain 197 code 24|org\.bluez\.Error\.InProgress|Abandoning connection attempt|attempt already abandoned|Server client connected|Broadcasting [0-9]+B to [1-9]|status=11/SEGV|Started bitchat\.service|meshPeers list now contains|bitchat-embedded [0-9.]+ \([0-9a-f]{12}'
KERNEL_INTEREST='Opcode 0x203[69] failed'

if [ -n "$APP_LOG" ] || [ -n "$KERNEL_LOG" ]; then
  [ -n "$APP_LOG" ] && [ -n "$KERNEL_LOG" ] || { echo "--app-log and --kernel-log go together" >&2; exit 2; }
  [ -r "$APP_LOG" ] || { echo "cannot read $APP_LOG" >&2; exit 1; }
  [ -r "$KERNEL_LOG" ] || { echo "cannot read $KERNEL_LOG" >&2; exit 1; }
  APP=$(grep -aE "$APP_INTEREST" "$APP_LOG" || true)
  KERN=$(grep -aE "$KERNEL_INTEREST" "$KERNEL_LOG" || true)
  SOURCE="$APP_LOG + $KERNEL_LOG"
  RELEASE="(from file)"
  NRESTARTS="n/a"
  EARLIEST=""
else
  [ -n "$HOST" ] || { echo "no target; set PI_HOST=user@host or pass --host user@host (or read saved journals with --app-log/--kernel-log)" >&2; exit 2; }
  SOURCE="$HOST"
  FETCH=$(ssh -o BatchMode=yes "$HOST" "SINCE='$SINCE'; UNTIL='$UNTIL'; APP_RE='$APP_INTEREST'; KERN_RE='$KERNEL_INTEREST'; "'
    echo "===APP==="
    journalctl -u bitchat.service --since "$SINCE" --until "$UNTIL" --no-pager 2>/dev/null | grep -aE "$APP_RE"
    echo "===KERN==="
    journalctl -k --since "$SINCE" --until "$UNTIL" --no-pager 2>/dev/null | grep -aE "$KERN_RE"
    echo "===META==="
    echo "earliest=$(journalctl --no-pager -o short-iso 2>/dev/null | head -1 | cut -d" " -f1)"
    echo "nrestarts=$(systemctl show bitchat.service -p NRestarts --value 2>/dev/null)"
    echo "release=$(sed -n "s/^release=//p" /opt/bitchat/releases/current/BUILD_INFO 2>/dev/null)"
  ' 2>/dev/null)

  if [ -z "$FETCH" ]; then
    echo "could not read the journal on $HOST" >&2
    exit 1
  fi

  APP=$(printf '%s\n' "$FETCH" | sed -n '/^===APP===$/,/^===KERN===$/p' | sed '1d;$d')
  KERN=$(printf '%s\n' "$FETCH" | sed -n '/^===KERN===$/,/^===META===$/p' | sed '1d;$d')
  META=$(printf '%s\n' "$FETCH" | sed -n '/^===META===$/,$p' | sed '1d')
  EARLIEST=$(printf '%s\n' "$META" | sed -n 's/^earliest=//p' | head -1)
  NRESTARTS=$(printf '%s\n' "$META" | sed -n 's/^nrestarts=//p' | head -1)
  RELEASE=$(printf '%s\n' "$META" | sed -n 's/^release=//p' | head -1)
fi

count() { printf '%s\n' "$1" | grep -acE "$2" || true; }

REQUESTS=$(count "$APP" 'Connect request:')
ESTABLISHED=$(count "$APP" 'BLUEZ_CLIENT\] INFO: Connected to [0-9A-F]{2}:')
FAILED=$(count "$APP" 'Failed to initiate connection')
ABORT=$(count "$APP" 'le-connection-abort-by-local')
TIMEOUT=$(count "$APP" 'domain 197 code 24')
INPROGRESS=$(count "$APP" 'org\.bluez\.Error\.InProgress')
REAPED=$(count "$APP" 'Abandoning connection attempt')
GUARD=$(count "$APP" 'attempt already abandoned')
INBOUND=$(count "$APP" 'Server client connected')
BROADCASTS=$(count "$APP" 'Broadcasting [0-9]+B to [1-9]')
SEGV=$(count "$APP" 'status=11/SEGV')
STARTS=$(count "$APP" 'Started bitchat\.service')
KERNADV=$(count "$KERN" 'Opcode 0x203[69] failed')

PEERS=$(printf '%s\n' "$APP" | grep -aoE 'meshPeers list now contains [0-9]+' | tail -1 | grep -oE '[0-9]+$')
IDENTITIES=$(printf '%s\n' "$APP" | grep -aoE 'bitchat-embedded [0-9.]+ \([0-9a-f]{12}' | grep -oE '[0-9a-f]{12}$' | sort -u)
IDENTITY_COUNT=$(printf '%s' "$IDENTITIES" | grep -c . || true)

echo "source:   $SOURCE"
[ -z "$APP_LOG" ] && echo "window:   $SINCE .. $UNTIL"
echo "release:  ${RELEASE:-unknown}"
echo
printf '  %-26s %s\n' "connect requests"        "$REQUESTS"
printf '  %-26s %s\n' "established"             "$ESTABLISHED"
printf '  %-26s %s\n' "failed to initiate"      "$FAILED"
printf '  %-26s %s\n' "  abort-by-local"        "$ABORT"
printf '  %-26s %s\n' "  gdbus timeout"         "$TIMEOUT"
printf '  %-26s %s\n' "InProgress"              "$INPROGRESS"
printf '  %-26s %s\n' "reaper abandonments"     "$REAPED"
printf '  %-26s %s\n' "late-success guard hits" "$GUARD"
printf '  %-26s %s\n' "inbound first writes"    "$INBOUND"
printf '  %-26s %s\n' "broadcasts with targets" "$BROADCASTS"
printf '  %-26s %s\n' "mesh peers (window end)" "${PEERS:-0}"
printf '  %-26s %s\n' "SEGV"                    "$SEGV"
printf '  %-26s %s\n' "service starts"          "$STARTS"
printf '  %-26s %s\n' "NRestarts (current)"     "${NRESTARTS:-unknown}"
echo
printf '  %-26s %s\n' "kernel adv failures"     "$KERNADV"

RATIO="n/a"
if [ "$REQUESTS" -gt 0 ]; then
  RATIO=$(awk -v a="$KERNADV" -v r="$REQUESTS" 'BEGIN { printf "%.2f", a / r }')
  printf '  %-26s %s per connect request\n' "  ratio" "$RATIO"
fi
echo

[ "$SEGV" -gt 0 ] && echo "WARNING: $SEGV SEGV in this window"
[ "$IDENTITY_COUNT" -gt 1 ] && echo "WARNING: window spans $IDENTITY_COUNT builds ($(printf '%s' "$IDENTITIES" | tr '\n' ' ')) -- counts mix binaries"
[ "$STARTS" -gt 1 ] && echo "note: service started $STARTS times in this window"

# journald here is size-capped and vacuums silently. It has already destroyed one window this plan
# measured, so the warning is not hypothetical. Timestamps are also not monotonic across a reboot on
# this board -- it has no RTC, so the clock jumps once NTP lands and an absolute window can miss data
# that exists. Prefer saved logs (--app-log) for anything you will want to re-read later.
[ -n "$EARLIEST" ] && echo "note: journal retains from $EARLIEST -- an earlier window is silently truncated"
echo

if [ "$REQUESTS" -eq 0 ]; then
  echo "IDLE - no outbound attempts in this window, nothing to judge"
elif [ "$ESTABLISHED" -gt 0 ] && [ "$KERNADV" -eq 0 ]; then
  echo "HEALTHY - $ESTABLISHED/$REQUESTS established, no kernel advertising failures"
elif [ "$ESTABLISHED" -eq 0 ] && awk -v x="$RATIO" 'BEGIN { exit !(x >= 0.75) }'; then
  echo "DEGRADED - 0/$REQUESTS established, $KERNADV kernel advertising failures ($RATIO per request)"
  echo "           capture now, while it is failing:"
  echo "           timeout 240 ssh -tt $HOST 'sudo -n btmon -w /tmp/degraded.btsnoop' | cat > /tmp/live.txt"
else
  echo "MIXED - $ESTABLISHED/$REQUESTS established, $KERNADV kernel advertising failures ($RATIO per request)"
fi

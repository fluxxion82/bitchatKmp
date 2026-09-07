#!/bin/sh
# Shipped inside the release directory and run as bitchat.service's ExecStartPre from
# /opt/bitchat/releases/current/wait-for-input-devices.sh.
#
# The app scans /dev/input/event* once at startup (KeyboardInput.findKeyboardDevice,
# TouchInput.findTouchDevice), so a device that appears a second later is simply missed for
# the whole run. Wait for both to exist AND for their event node to be readable by this user:
# the sysfs name shows up as soon as the kernel registers the device, while udev applies the
# permissions asynchronously, so a name alone does not mean the app can open() it.
#
# Never fails: on timeout it says what is missing and still exits 0, so a missing keyboard or
# touchscreen degrades the app instead of blocking boot.
#
# Living in a script rather than an inline ExecStartPre= also keeps systemd's "$" specifier
# expansion out of the picture.
#
# Usage: wait-for-input-devices.sh [timeout-seconds]   (default 20; handy for running by hand)
# POSIX sh only: no seq, no arrays, no bashisms.
set -u

KEYBOARD_NAME="CardKb-I2C"
TOUCH_NAME="XPT2046 Touchscreen"

TIMEOUT="${1:-20}"
case "$TIMEOUT" in
    '' | *[!0-9]*)
        echo "bitchat: ignoring non-numeric timeout '$TIMEOUT', using 20 s"
        TIMEOUT=20
        ;;
esac

# Echoes the /dev/input/eventN node whose sysfs name matches exactly and is readable now,
# or nothing (exit 1). An unmatched glob leaves the pattern itself in "$f"; the -r test
# rejects it, so /sys not existing at all (a dev machine) is handled by the same branch.
#
# The name is on the parent input device, not the event device: /sys/class/input/eventN has no
# "name", but its "device" symlink points at the inputM directory that does. Measured on the Pi:
#   /sys/class/input/event1/device/name = CardKb-I2C
#   /sys/class/input/event2/device/name = XPT2046 Touchscreen
# so the node is two dirnames up from the name file, not one.
node_for() {
    want="$1"
    for f in /sys/class/input/event*/device/name; do
        [ -r "$f" ] || continue
        name=$(cat "$f" 2>/dev/null) || continue
        [ "$name" = "$want" ] || continue
        node="/dev/input/$(basename "$(dirname "$(dirname "$f")")")"
        [ -r "$node" ] || continue
        echo "$node"
        return 0
    done
    return 1
}

elapsed=0
keyboard=""
touch=""
while :; do
    [ -n "$keyboard" ] || keyboard=$(node_for "$KEYBOARD_NAME")
    [ -n "$touch" ] || touch=$(node_for "$TOUCH_NAME")
    if [ -n "$keyboard" ] && [ -n "$touch" ]; then
        echo "bitchat: input devices ready after ${elapsed} s (keyboard $keyboard, touch $touch)"
        exit 0
    fi
    [ "$elapsed" -lt "$TIMEOUT" ] || break
    sleep 1
    elapsed=$((elapsed + 1))
done

missing=""
[ -n "$keyboard" ] || missing="keyboard '$KEYBOARD_NAME'"
[ -n "$touch" ] || missing="${missing:+$missing and }touch '$TOUCH_NAME'"
echo "bitchat: no readable $missing after ${TIMEOUT} s, starting anyway"
exit 0

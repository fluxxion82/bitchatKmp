#!/usr/bin/env bash
# Verdict tests for scripts/ble-outbound-report.sh, run against saved journal text.
#
# The degraded fixture matters: journald on the Pi vacuumed the real window this was built from, so
# these files are the only copy of what that state looked like.
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 1

REPORT=scripts/ble-outbound-report.sh
pass=0; fail=0

check() { # check <label> <expected substring> <actual output>
  if printf '%s' "$3" | grep -qF "$2"; then
    pass=$((pass + 1))
  else
    fail=$((fail + 1))
    echo "FAIL: $1"
    echo "  expected to find: $2"
    echo "  in:"; printf '%s\n' "$3" | sed 's/^/    /'
  fi
}

row() { # row <label> <row label> <expected value> <actual output> -- tolerant of column padding
  if printf '%s' "$4" | grep -qE "^ *$(printf '%s' "$2" | sed 's/[][()\\.*^$]/\\&/g') +$3\$"; then
    pass=$((pass + 1))
  else
    fail=$((fail + 1))
    echo "FAIL: $1 (expected row \"$2\" to be $3)"
    printf '%s\n' "$4" | grep -E "$(printf '%s' "$2" | sed 's/[][()\\.*^$]/\\&/g')" | sed 's/^/    /'
  fi
}

D=$("$REPORT" --app-log tests/ble-outbound-report/app-degraded.log \
               --kernel-log tests/ble-outbound-report/kernel-degraded.log 2>&1)
check "degraded verdict"          "DEGRADED - 0/16 established"   "$D"
check "degraded ratio"            "1.00 per connect request"      "$D"
row   "degraded counts requests"  "connect requests"        16    "$D"
row   "degraded counts aborts"    "abort-by-local"          11    "$D"
row   "degraded counts timeouts"  "gdbus timeout"           5     "$D"
check "degraded suggests capture" "sudo -n btmon"                 "$D"

H=$("$REPORT" --app-log tests/ble-outbound-report/app-healthy.log \
               --kernel-log tests/ble-outbound-report/kernel-healthy.log 2>&1)
check "healthy verdict"           "HEALTHY - 1/1 established"     "$H"
row   "healthy peers"             "mesh peers (window end)" 2     "$H"
row   "healthy broadcasts"        "broadcasts with targets" 82    "$H"

I=$("$REPORT" --app-log tests/ble-outbound-report/kernel-healthy.log \
               --kernel-log tests/ble-outbound-report/kernel-healthy.log 2>&1)
check "idle verdict"              "IDLE"                          "$I"

# A window spanning two deployments mixes binaries and must say so.
cat tests/ble-outbound-report/app-degraded.log tests/ble-outbound-report/app-healthy.log > /tmp/ble-mixed.log
M=$("$REPORT" --app-log /tmp/ble-mixed.log \
               --kernel-log tests/ble-outbound-report/kernel-degraded.log 2>&1)
check "mixed builds warned"       "window spans 2 builds"         "$M"
check "mixed verdict"             "MIXED - 1/17 established"      "$M"
rm -f /tmp/ble-mixed.log

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]

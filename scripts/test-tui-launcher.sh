#!/usr/bin/env bash
# Runs the terminal UI's tmux launcher, tmux config and attach script (apps/embedded/systemd) against
# the tmux the board installs, in a Debian container: session start with only stdin a terminal (as
# under the unit), every key reaching the app, a second client with an unknown TERM, re-attach of
# the console client, exit-status passthrough (0, 75), server death, and the fallbacks to a direct
# start. A stand-in script plays the app. Needs Docker; not part of verify.sh.
#
#   scripts/test-tui-launcher.sh            # debian:trixie, what the boards run
#   IMAGE=debian:bookworm scripts/test-tui-launcher.sh
set -euo pipefail
cd "$(dirname "$0")/.."
IMAGE="${IMAGE:-debian:trixie}"
docker run --rm -v "$PWD/apps/embedded/systemd:/src:ro" -v "$PWD/scripts/tui-launcher-test:/t:ro" "$IMAGE" bash -c '
set -e
apt-get update -qq >/dev/null 2>&1
DEBIAN_FRONTEND=noninteractive apt-get install -y -qq tmux python3-minimal procps ncurses-bin >/dev/null 2>&1
mkdir /rel
cp /src/bitchat-tui-launcher /src/bitchat-tui-attach /src/bitchat-tui.tmux.conf /rel/
cp /t/fake-kexe /rel/bitchat-tui.kexe
chmod +x /rel/bitchat-tui-launcher /rel/bitchat-tui-attach /rel/bitchat-tui.kexe
install -m 755 /t/fake-systemd-cat /usr/local/bin/systemd-cat
useradd -m tester && chown -R tester /rel
su tester -c "cd /rel && python3 /t/harness.py"
'

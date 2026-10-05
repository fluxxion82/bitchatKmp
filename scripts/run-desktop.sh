#!/usr/bin/env bash
# Build the required desktop native library, stage the app, and launch it.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
APP="${1:-compose}"
case "$APP" in
  compose|tui) ;;
  *) echo "Usage: scripts/run-desktop.sh [compose|tui]" >&2; exit 2 ;;
esac
if [ "$APP" = tui ] && [ ! -t 0 ]; then
  echo "Run the TUI launcher from an interactive terminal." >&2
  exit 2
fi
bash data/remote/tor/native/build-desktop.sh --install
if [ "$APP" = tui ]; then
  ./gradlew -Pembedded.enabled=false -Ptui.enabled=true :apps:desktop:tui:installDist --console=plain
  exec apps/desktop/tui/build/install/bitchat-tui/bin/bitchat-tui
else
  exec ./gradlew -Pembedded.enabled=false -Ptui.enabled=false :apps:desktop:compose:run --console=plain
fi

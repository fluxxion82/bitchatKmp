#!/usr/bin/env bash
set -euo pipefail

# Aggregate all Linux native library builds used by the KMP project.
#
# Currently supports:
# - linuxArm64 (Raspberry Pi / embedded ARM64)
#
# Uses Docker for cross-compilation from macOS/Linux x86_64 host.
# Prerequisites:
# - Docker must be installed and running

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "==> Building Linux native libraries"

# Linux ARM64 (cross-compiled via Docker)
echo "==> Building linuxArm64 targets..."
"${SCRIPT_DIR}/build-native-linux-arm64.sh" "$@"

# TODO: Add linuxX64 support when needed
# echo "==> Building linuxX64 targets..."
# "${SCRIPT_DIR}/build-native-linux-x64.sh" "$@"

echo "==> All Linux native libraries built"
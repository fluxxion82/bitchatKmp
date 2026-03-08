#!/usr/bin/env bash
set -euo pipefail

# Build all native libraries across supported platforms (iOS, Android, Desktop, Linux).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "==> Building iOS native libraries"
"${SCRIPT_DIR}/build-all-ios.sh" "$@"

echo "==> Building Android native libraries"
"${SCRIPT_DIR}/build-all-android.sh" "$@"

echo "==> Building desktop native libraries"
"${SCRIPT_DIR}/build-all-desktop.sh" "$@"

echo "==> Building Linux native libraries"
"${SCRIPT_DIR}/build-all-linux.sh" "$@"

echo "==> All platform native libraries built"

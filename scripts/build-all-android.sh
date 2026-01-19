#!/usr/bin/env bash
set -euo pipefail

# Aggregate all Android native library builds used by the KMP project.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

SCRIPTS=(
  "${ROOT_DIR}/data/remote/tor/native/build-android.sh"
)

for script in "${SCRIPTS[@]}"; do
  echo "==> Running $(basename "$script")"
  if [[ ! -x "$script" ]]; then
    chmod +x "$script"
  fi
  "$script" "$@"
done

echo "==> All Android native libraries built"

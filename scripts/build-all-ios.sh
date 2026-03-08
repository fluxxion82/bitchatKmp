#!/usr/bin/env bash
set -euo pipefail

# Aggregate all native iOS library builds used by the KMP project.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

SCRIPTS=(
  "${ROOT_DIR}/data/noise/native/build-ios.sh"
  "${ROOT_DIR}/data/crypto/native/build-libsodium-ios.sh"
  "${ROOT_DIR}/data/crypto/native/build-secp256k1-ios.sh"
  "${ROOT_DIR}/data/remote/tor/native/build-ios.sh"
)

for script in "${SCRIPTS[@]}"; do
  echo "==> Running $(basename "$script")"
  if [[ ! -x "$script" ]]; then
    chmod +x "$script"
  fi
  "$script" "$@"
done

echo "==> All iOS native libraries built"

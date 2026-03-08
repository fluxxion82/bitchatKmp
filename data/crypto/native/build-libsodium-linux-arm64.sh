#!/usr/bin/env bash
set -euo pipefail

# Cross-compile libsodium for linuxArm64
# This script must be run inside the Docker container - do NOT run directly!
#
# Use the master build script instead:
#   ./scripts/build-native-linux-arm64.sh
#
# Or manually via Docker:
#   docker run --rm -v $(pwd):/build bitchat-linux-arm64-cross bash build-libsodium-linux-arm64.sh

# Check if running inside Docker with cross-compiler available
if ! command -v aarch64-linux-gnu-gcc &> /dev/null; then
    echo "=========================================="
    echo "ERROR: Cross-compiler not found!"
    echo "=========================================="
    echo ""
    echo "This script must be run inside the Docker container."
    echo "Do NOT run it directly on your host machine."
    echo ""
    echo "Use the master build script instead:"
    echo "  ./scripts/build-native-linux-arm64.sh"
    echo ""
    echo "Or to build all Linux libraries:"
    echo "  ./scripts/build-all-linux.sh"
    echo "=========================================="
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC_DIR="${SCRIPT_DIR}/libsodium"
BUILD_DIR="${SCRIPT_DIR}/libsodium/build/linux-arm64"

echo "==> Building libsodium for linuxArm64"

if [[ ! -d "${SRC_DIR}" ]]; then
    echo "ERROR: libsodium source not found at ${SRC_DIR}" >&2
    echo "Please ensure libsodium is checked out in data/crypto/native/libsodium/" >&2
    exit 1
fi

# Create build output directory
mkdir -p "${BUILD_DIR}"

cd "${SRC_DIR}"

# Clean previous builds
make distclean >/dev/null 2>&1 || true

# Always regenerate autotools files to avoid version mismatches
# (host may have different automake version than Docker container)
echo "==> Regenerating autotools files..."
autoreconf -i

echo "==> Configuring libsodium for aarch64-linux-gnu..."
./configure \
    --host=aarch64-linux-gnu \
    --prefix="${BUILD_DIR}" \
    --disable-shared \
    --enable-static \
    --enable-minimal \
    CC=aarch64-linux-gnu-gcc \
    AR=aarch64-linux-gnu-ar \
    RANLIB=aarch64-linux-gnu-ranlib \
    CFLAGS="-O2"

echo "==> Building libsodium..."
make -j"$(nproc)"

echo "==> Installing libsodium..."
make install

echo "==> libsodium build complete!"
echo "Static library: ${BUILD_DIR}/lib/libsodium.a"
echo "Headers: ${BUILD_DIR}/include/"
ls -lh "${BUILD_DIR}/lib/"*.a

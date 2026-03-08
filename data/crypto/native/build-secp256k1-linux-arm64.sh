#!/usr/bin/env bash
set -euo pipefail

# Cross-compile secp256k1 for linuxArm64
# This script must be run inside the Docker container - do NOT run directly!
#
# Use the master build script instead:
#   ./scripts/build-native-linux-arm64.sh
#
# Or manually via Docker:
#   docker run --rm -v $(pwd):/build bitchat-linux-arm64-cross bash build-secp256k1-linux-arm64.sh

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
SECP256K1_DIR="${SCRIPT_DIR}/secp256k1"
BUILD_DIR="${SECP256K1_DIR}/build/linux-arm64"

echo "==> Building secp256k1 for linuxArm64"

if [[ ! -d "${SECP256K1_DIR}" ]]; then
    echo "secp256k1 directory not found at ${SECP256K1_DIR}" >&2
    exit 1
fi

# Create build output directory
mkdir -p "${BUILD_DIR}"

cd "${SECP256K1_DIR}"

# Clean previous builds
make distclean >/dev/null 2>&1 || true

# Always regenerate autotools files to avoid version mismatches
# (host may have different automake version than Docker container)
echo "==> Regenerating autotools files..."
autoreconf -i

echo "==> Configuring secp256k1 for aarch64-linux-gnu..."
./configure \
    --host=aarch64-linux-gnu \
    --prefix="${BUILD_DIR}" \
    --disable-shared \
    --enable-static \
    --enable-module-recovery \
    --enable-module-ecdh \
    --enable-module-schnorrsig \
    --enable-module-extrakeys \
    --disable-benchmark \
    --disable-tests \
    --disable-exhaustive-tests \
    CC=aarch64-linux-gnu-gcc \
    AR=aarch64-linux-gnu-ar \
    RANLIB=aarch64-linux-gnu-ranlib \
    CFLAGS="-O2"

echo "==> Building secp256k1..."
make -j"$(nproc)"

echo "==> Installing secp256k1..."
make install

echo "==> secp256k1 build complete!"
echo "Static library: ${BUILD_DIR}/lib/libsecp256k1.a"
echo "Headers: ${BUILD_DIR}/include/"
ls -lh "${BUILD_DIR}/lib/"*.a

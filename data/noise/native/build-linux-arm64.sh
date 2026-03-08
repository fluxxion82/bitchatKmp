#!/usr/bin/env bash
set -euo pipefail

# Cross-compile noise-c for linuxArm64
# This script must be run inside the Docker container - do NOT run directly!
#
# Use the master build script instead:
#   ./scripts/build-native-linux-arm64.sh
#
# Or manually via Docker:
#   docker run --rm -v $(pwd):/build bitchat-linux-arm64-cross bash build-linux-arm64.sh

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
NOISE_DIR="${SCRIPT_DIR}/noise-c"
BUILD_DIR="${NOISE_DIR}/build/linux-arm64"

# Path to cross-compiled libsodium (built separately)
# When running in Docker with the master build script, this is mounted at /crypto
LIBSODIUM_DIR="/crypto/libsodium/build/linux-arm64"

echo "==> Building noise-c for linuxArm64"

if [[ ! -d "${NOISE_DIR}" ]]; then
    echo "noise-c directory not found at ${NOISE_DIR}" >&2
    exit 1
fi

# Create build output directory
mkdir -p "${BUILD_DIR}"

cd "${NOISE_DIR}"

# Clean previous builds
make distclean >/dev/null 2>&1 || true

# Always regenerate autotools files to avoid version mismatches
# (host may have different automake version than Docker container)
echo "==> Regenerating autotools files..."
autoreconf -i

# Check if libsodium is available for linking
# This avoids symbol conflicts with OpenSSL's poly1305
LIBSODIUM_OPTS=""
if [[ -d "${LIBSODIUM_DIR}/lib" ]] && [[ -f "${LIBSODIUM_DIR}/lib/libsodium.a" ]]; then
    echo "==> Using libsodium from ${LIBSODIUM_DIR}"
    LIBSODIUM_OPTS="--with-libsodium --without-openssl"
    export libsodium_CFLAGS="-I${LIBSODIUM_DIR}/include"
    export libsodium_LIBS="-L${LIBSODIUM_DIR}/lib -lsodium"
else
    echo "==> Warning: libsodium not found, using built-in crypto (may cause conflicts)"
fi

echo "==> Configuring noise-c for aarch64-linux-gnu..."
./configure \
    --host=aarch64-linux-gnu \
    --prefix="${BUILD_DIR}" \
    --disable-shared \
    --enable-static \
    ${LIBSODIUM_OPTS} \
    CC=aarch64-linux-gnu-gcc \
    AR=aarch64-linux-gnu-ar \
    RANLIB=aarch64-linux-gnu-ranlib \
    CFLAGS="-O2"

echo "==> Building noise-c..."
make -j"$(nproc)"

echo "==> Installing noise-c..."
make install

echo "==> noise-c build complete!"
echo "Static libraries:"
ls -lh "${BUILD_DIR}/lib/"*.a
echo "Headers: ${BUILD_DIR}/include/"

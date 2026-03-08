#!/bin/bash
# Cross-compile GattLib for linuxArm64 (Raspberry Pi / embedded ARM64)
#
# This script is designed to run inside the bitchat-linux-arm64-cross Docker container.
# GattLib provides a C library for BLE GATT client operations using BlueZ/D-Bus.
#
# Prerequisites (provided by Docker image):
# - ARM64 cross-compiler toolchain
# - CMake 3.22+
# - ARM64 GLib, D-Bus, BlueZ libraries via multiarch
#
# Usage (from Docker container):
#   bash /build/build-gattlib-linux-arm64.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GATTLIB_DIR="${SCRIPT_DIR}/gattlib"
BUILD_DIR="${GATTLIB_DIR}/build/linux-arm64"
INSTALL_DIR="${BUILD_DIR}/install"

# Cross-compilation settings - use multiarch paths
ARM64_LIB_DIR="/usr/lib/aarch64-linux-gnu"
ARM64_INCLUDE_DIR="/usr/include"

echo "=========================================="
echo "Building GattLib for linuxArm64"
echo "=========================================="
echo "GATTLIB_DIR: ${GATTLIB_DIR}"
echo "BUILD_DIR: ${BUILD_DIR}"
echo "ARM64_LIB_DIR: ${ARM64_LIB_DIR}"
echo

# Verify gattlib source exists
if [ ! -f "${GATTLIB_DIR}/CMakeLists.txt" ]; then
    echo "ERROR: GattLib source not found at ${GATTLIB_DIR}"
    echo "Make sure the gattlib submodule is initialized:"
    echo "  git submodule update --init data/remote/transport/bluetooth/native/gattlib"
    exit 1
fi

# Clean previous build
rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"
cd "${BUILD_DIR}"

echo "==> Configuring CMake..."

# Create CMake toolchain file for cross-compilation
cat > toolchain-arm64.cmake << EOF
set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR aarch64)

set(CMAKE_C_COMPILER aarch64-linux-gnu-gcc)
set(CMAKE_CXX_COMPILER aarch64-linux-gnu-g++)

# Use multiarch paths for ARM64 libraries
set(CMAKE_FIND_ROOT_PATH ${ARM64_LIB_DIR})
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE BOTH)

# Library search paths
set(CMAKE_LIBRARY_PATH ${ARM64_LIB_DIR})
link_directories(${ARM64_LIB_DIR})
EOF

# Configure with CMake
# Note: We build gattlib with D-Bus backend for BlueZ 5.x support
# GATTLIB_SHARED_LIB=OFF builds a static library
cmake "${GATTLIB_DIR}" \
    -DCMAKE_TOOLCHAIN_FILE=toolchain-arm64.cmake \
    -DCMAKE_INSTALL_PREFIX="${INSTALL_DIR}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DGATTLIB_BUILD_EXAMPLES=OFF \
    -DGATTLIB_BUILD_DOCS=OFF \
    -DGATTLIB_PYTHON_INTERFACE=OFF \
    -DGATTLIB_FORCE_DBUS=ON \
    -DGATTLIB_SHARED_LIB=OFF \
    -DCMAKE_C_FLAGS="-fPIC" \
    -DCMAKE_EXE_LINKER_FLAGS="-L${ARM64_LIB_DIR}"

echo
echo "==> Building..."
make -j$(nproc) VERBOSE=1

echo
echo "==> Installing..."
make install

echo
echo "=========================================="
echo "GattLib build complete!"
echo "=========================================="
echo
echo "Output files:"
ls -la "${INSTALL_DIR}/lib/" 2>/dev/null || echo "(check ${INSTALL_DIR} for output)"
echo
echo "Include files:"
ls -la "${INSTALL_DIR}/include/" 2>/dev/null || echo "(check ${INSTALL_DIR}/include for headers)"

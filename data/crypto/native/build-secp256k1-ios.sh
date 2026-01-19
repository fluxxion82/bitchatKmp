#!/usr/bin/env bash
set -euo pipefail

# Build secp256k1 for iOS platforms
# This script builds secp256k1 static libraries for iOS device and simulator architectures

# ---- Configuration ----
IOS_MIN=13.0

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SECP256K1_DIR="${SCRIPT_DIR}/secp256k1"

if [[ ! -d "${SECP256K1_DIR}" ]]; then
  echo "secp256k1 directory not found at ${SECP256K1_DIR}" >&2
  exit 1
fi

# Where to place the per-arch "installed" outputs
OUT_ROOT="${SECP256K1_DIR}/build"
mkdir -p "$OUT_ROOT"

sdk_path() { xcrun --sdk "$1" --show-sdk-path; }
cc_for()   { xcrun --sdk "$1" clang; }
ar_for()   { xcrun --sdk "$1" ar; }
ranlib_for(){ xcrun --sdk "$1" ranlib; }

build_one () {
  local NAME="$1" SDK="$2" ARCH="$3" HOST="$4" MINFLAG="$5"
  local PREFIX="${OUT_ROOT}/${NAME}"
  local SYSROOT="$(sdk_path "$SDK")"

  echo "==> Building secp256k1 for $NAME (SDK=$SDK, ARCH=$ARCH, HOST=$HOST)"

  pushd "${SECP256K1_DIR}" >/dev/null

  # Clean previous builds
  make distclean >/dev/null 2>&1 || true

  # Run autogen if configure doesn't exist
  if [[ ! -f "./configure" ]]; then
    echo "Running autogen.sh..."
    ./autogen.sh
  fi

  ./configure \
    --host="${HOST}" \
    --disable-shared --enable-static \
    --enable-module-recovery \
    --enable-module-ecdh \
    --enable-module-schnorrsig \
    --enable-module-extrakeys \
    --disable-benchmark \
    --disable-tests \
    --disable-exhaustive-tests \
    CC="$(cc_for "$SDK")" \
    AR="$(ar_for "$SDK")" \
    RANLIB="$(ranlib_for "$SDK")" \
    CFLAGS="-arch ${ARCH} -isysroot ${SYSROOT} ${MINFLAG} -O2" \
    LDFLAGS="-arch ${ARCH} -isysroot ${SYSROOT}" \
    --prefix="${PREFIX}"

  make -j"$(sysctl -n hw.ncpu)"
  make install

  popd >/dev/null

  echo "Installed to: ${PREFIX}"
  echo "Static lib:"
  ls -lh "${PREFIX}/lib"/*.a
  echo
}

# iOS device (arm64)
build_one "ios-arm64" iphoneos arm64 arm-apple-darwin "-miphoneos-version-min=${IOS_MIN}"

# iOS Simulator (x86_64)
build_one "ios-x64" iphonesimulator x86_64 x86_64-apple-darwin "-mios-simulator-version-min=${IOS_MIN}"

# iOS Simulator (arm64)
build_one "ios-sim-arm64" iphonesimulator arm64 arm-apple-darwin "-mios-simulator-version-min=${IOS_MIN}"

echo "==> Creating fat library for simulator..."
# Create a fat library for simulator that includes both architectures
SIMULATOR_FAT="${OUT_ROOT}/ios-simulator-fat"
mkdir -p "${SIMULATOR_FAT}/lib" "${SIMULATOR_FAT}/include"

lipo -create \
  "${OUT_ROOT}/ios-x64/lib/libsecp256k1.a" \
  "${OUT_ROOT}/ios-sim-arm64/lib/libsecp256k1.a" \
  -output "${SIMULATOR_FAT}/lib/libsecp256k1.a"

# Copy headers from one of the builds (they're all the same)
cp -r "${OUT_ROOT}/ios-arm64/include/"* "${SIMULATOR_FAT}/include/"

echo "==> All builds complete!"
echo "Output directories:"
ls -d "${OUT_ROOT}"/ios-*
echo
echo "Device library: ${OUT_ROOT}/ios-arm64/lib/libsecp256k1.a"
echo "Simulator library (fat): ${SIMULATOR_FAT}/lib/libsecp256k1.a"
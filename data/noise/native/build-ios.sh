#!/usr/bin/env bash
set -euo pipefail

# ---- tweak these if you need to ----
IOS_MIN=13.0

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NOISE_DIR="${SCRIPT_DIR}/noise-c"
if [[ ! -d "${NOISE_DIR}" ]]; then
  echo "noise-c directory not found at ${NOISE_DIR}" >&2
  exit 1
fi

# Where to place the per-arch "installed" outputs
OUT_ROOT="${NOISE_DIR}/build"
mkdir -p "$OUT_ROOT"

sdk_path() { xcrun --sdk "$1" --show-sdk-path; }
cc_for()   { xcrun --sdk "$1" clang; }
ar_for()   { xcrun --sdk "$1" ar; }
ranlib_for(){ xcrun --sdk "$1" ranlib; }

build_one () {
  local NAME="$1" SDK="$2" ARCH="$3" HOST="$4" MINFLAG="$5"
  local PREFIX="${OUT_ROOT}/${NAME}"
  local SYSROOT="$(sdk_path "$SDK")"
  echo "==> Building $NAME (SDK=$SDK, ARCH=$ARCH, HOST=$HOST)"
  pushd "${NOISE_DIR}" >/dev/null
  make distclean >/dev/null 2>&1 || true

  ./configure \
    --host="${HOST}" \
    --disable-shared --enable-static \
    CC="$(cc_for "$SDK")" \
    AR="$(ar_for "$SDK")" \
    RANLIB="$(ranlib_for "$SDK")" \
    CFLAGS="-arch ${ARCH} -isysroot ${SYSROOT} ${MINFLAG}" \
    LDFLAGS="-arch ${ARCH} -isysroot ${SYSROOT}" \
    --prefix="${PREFIX}"

  make -j"$(sysctl -n hw.ncpu)"
  make install
  popd >/dev/null

  echo "Installed to: ${PREFIX}"
  echo "Static lib(s):"
  ls -1 "${PREFIX}/lib"/*.a
  echo
}

# iOS device (arm64)
build_one "ios-arm64" iphoneos arm64 arm-apple-darwin "-miphoneos-version-min=${IOS_MIN}"

# iOS Simulator (x86_64)
build_one "ios-x64" iphonesimulator x86_64 x86_64-apple-darwin "-mios-simulator-version-min=${IOS_MIN}"

# iOS Simulator (arm64)
build_one "ios-sim-arm64" iphonesimulator arm64 arm-apple-darwin "-mios-simulator-version-min=${IOS_MIN}"

#!/usr/bin/env bash
set -euo pipefail

# ---- tweak these if you need to ----
MACOS_MIN=11.0

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NOISE_DIR="${SCRIPT_DIR}/noise-c"
if [[ ! -d "${NOISE_DIR}" ]]; then
  echo "noise-c directory not found at ${NOISE_DIR}" >&2
  exit 1
fi

# Where to place the per-arch "installed" outputs
OUT_ROOT="${NOISE_DIR}/build"
mkdir -p "$OUT_ROOT"

sdk_path() { xcrun --sdk macosx --show-sdk-path; }
cc_for()   { xcrun --sdk macosx clang; }
ar_for()   { xcrun --sdk macosx ar; }
ranlib_for(){ xcrun --sdk macosx ranlib; }

build_one () {
  local NAME="$1" ARCH="$2" HOST="$3"
  local PREFIX="${OUT_ROOT}/${NAME}"
  local SYSROOT="$(sdk_path)"
  echo "==> Building $NAME (SDK=macosx, ARCH=$ARCH, HOST=$HOST)"
  pushd "${NOISE_DIR}" >/dev/null
  make distclean >/dev/null 2>&1 || true

  ./configure \
    --host="${HOST}" \
    --disable-shared --enable-static \
    CC="$(cc_for)" \
    AR="$(ar_for)" \
    RANLIB="$(ranlib_for)" \
    CFLAGS="-arch ${ARCH} -isysroot ${SYSROOT} -mmacosx-version-min=${MACOS_MIN}" \
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

# macOS Apple Silicon (arm64)
build_one "macos-arm64" arm64 aarch64-apple-darwin

# macOS Intel (x86_64)
build_one "macos-x64" x86_64 x86_64-apple-darwin

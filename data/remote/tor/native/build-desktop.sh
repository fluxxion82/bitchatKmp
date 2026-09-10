#!/usr/bin/env bash
#
# Build Arti native library for Desktop (JVM)
#
# Requirements:
#   - Rust toolchain (https://rustup.rs/)
#
# Usage:
#   ./build-desktop.sh              # Build for host platform
#   ./build-desktop.sh --clean      # Clean and rebuild

set -euo pipefail

# ==============================================================================
# Configuration
# ==============================================================================

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ARTI_SOURCE_DIR="$SCRIPT_DIR/arti"
WRAPPER_DIR="$SCRIPT_DIR/arti-desktop-wrapper"
LIBS_DIR="$SCRIPT_DIR/libs/desktop"

# Where the compile actually happens.
#
# Never inside the repository. This checkout can live on an ntfs3 volume, and a Cargo build of Arti
# there has corrupted the filesystem: directories become unreadable ("ls: Invalid argument") and the
# Gradle build starts failing in unrelated ways. CARGO_TARGET_DIR alone does not avoid it, because
# the wrapper depends on Arti through *path* dependencies (../arti/crates/...), so Cargo reads and
# writes across the whole tree. Both trees are copied out instead.
BUILD_ROOT="${BITCHAT_ARTI_BUILD_ROOT:-$HOME/.cache/bitchat-arti}"

# Where a successful build is left. Deliberately NOT $LIBS_DIR: apps/desktop/build.gradle.kts
# stages every .so from there into the application resources, so copying there would put a library
# that has not been through the native-correctness review into the next run of the app.
OUTPUT_DIR="$BUILD_ROOT/output"

# Read pinned version
VERSION="$(tr -d '[:space:]' < "$SCRIPT_DIR/ARTI_VERSION")"

CLEAN_BUILD=false
INSTALL_LIB=false
ALLOW_CLONE=false
JOBS="${BITCHAT_ARTI_JOBS:-$(( $(nproc 2>/dev/null || echo 4) / 2 ))}"
[ "$JOBS" -lt 1 ] && JOBS=1

# Parse arguments
while [[ $# -gt 0 ]]; do
  case "$1" in
    --clean) CLEAN_BUILD=true; shift ;;
    --install) INSTALL_LIB=true; shift ;;
    --allow-clone) ALLOW_CLONE=true; shift ;;
    --help|-h)
      cat <<'USAGE'
Usage: build-desktop.sh [--clean] [--allow-clone] [--install]

  --clean         Remove the out-of-tree build directory first.
  --allow-clone   Permit cloning Arti when the submodule is not initialised.
                  Prefer: git submodule update --init data/remote/tor/native/arti
  --install       Copy the built library into native/libs/desktop.

                  WITHOUT THIS the library is left in the build directory and the
                  application will not load it. The desktop build stages every .so from
                  native/libs/desktop into the app, so installing makes it live on the
                  next run. Panics now unwind and are caught at each JNI export, but
                  the library has never been exercised against a real Tor network from
                  this app -- install when you are ready to test that, not by habit.

Environment:
  BITCHAT_ARTI_BUILD_ROOT   Build directory (default ~/.cache/bitchat-arti)
  BITCHAT_ARTI_JOBS         Parallel jobs (default: half the CPUs)
USAGE
      exit 0
      ;;
    *)
      echo -e "${RED}Error: Unknown argument: $1${NC}"
      exit 1
      ;;
  esac
done

# Detect platform
OS="$(uname -s)"
case "$OS" in
  Darwin)
    LIB_EXT="dylib"
    PLATFORM="macOS"
    ;;
  Linux)
    LIB_EXT="so"
    PLATFORM="Linux"
    ;;
  MINGW*|MSYS*|CYGWIN*)
    LIB_EXT="dll"
    PLATFORM="Windows"
    ;;
  *)
    echo -e "${RED}Unsupported platform: $OS${NC}"
    exit 1
    ;;
esac

# ==============================================================================
# Functions
# ==============================================================================

print_header() { echo -e "${BLUE}=========================================${NC}\n${BLUE}$1${NC}\n${BLUE}=========================================${NC}"; }
print_success() { echo -e "${GREEN}✓ $1${NC}"; }
print_error() { echo -e "${RED}✗ $1${NC}"; }
print_info() { echo -e "${YELLOW}ℹ $1${NC}"; }

check_prerequisites() {
  print_header "Checking Prerequisites"

  if ! command -v rustc >/dev/null 2>&1; then
    print_error "Rust not installed (https://rustup.rs/)"
    exit 1
  fi
  print_success "Rust $(rustc --version | cut -d' ' -f2)"

  mkdir -p "$BUILD_ROOT"
  local fstype
  fstype="$(df -T "$BUILD_ROOT" 2>/dev/null | awk 'NR==2 {print $2}')"
  case "$fstype" in
    ntfs|ntfs3|fuseblk|exfat|vfat)
      print_error "Build directory is on $fstype: $BUILD_ROOT"
      print_error "Arti has corrupted this filesystem when built on it."
      print_error "Set BITCHAT_ARTI_BUILD_ROOT to a path on ext4/btrfs/xfs."
      exit 1
      ;;
  esac
  print_success "Build directory on $fstype: $BUILD_ROOT"

  local avail_gb
  avail_gb="$(df -BG --output=avail "$BUILD_ROOT" 2>/dev/null | awk 'NR==2 {gsub(/G/,""); print $1}')"
  if [ -n "$avail_gb" ] && [ "$avail_gb" -lt 15 ]; then
    print_error "Only ${avail_gb}G free at $BUILD_ROOT; Arti needs roughly 10-15G of build output."
    exit 1
  fi
  print_success "${avail_gb:-?}G free, building with -j$JOBS"

  echo ""
}

prepare_sources() {
  print_header "Preparing Arti $VERSION"

  if [ "$CLEAN_BUILD" = true ] && [ -d "$BUILD_ROOT/src" ]; then
    print_info "Removing previous out-of-tree build..."
    rm -rf "$BUILD_ROOT/src"
  fi

  if [ -z "$(ls -A "$ARTI_SOURCE_DIR" 2>/dev/null)" ]; then
    # The submodule is a gitlink pinned to a specific commit. Cloning the tag instead is not the
    # same thing: the tag can move, and it is not necessarily the commit this repository pins.
    if [ "$ALLOW_CLONE" != true ]; then
      print_error "$ARTI_SOURCE_DIR is empty (submodule not initialised)."
      print_info  "Preferred:  git submodule update --init --recursive $ARTI_SOURCE_DIR"
      print_info  "Otherwise:  re-run with --allow-clone to fetch tag $VERSION instead,"
      print_info  "            which is NOT guaranteed to match the pinned commit."
      exit 1
    fi
    print_info "Cloning Arti from GitLab (tag $VERSION, not the pinned commit)..."
    git clone --depth 1 --branch "$VERSION" \
      https://gitlab.torproject.org/tpo/core/arti.git "$ARTI_SOURCE_DIR"
  fi

  local head
  head="$(git -C "$ARTI_SOURCE_DIR" rev-parse HEAD 2>/dev/null || echo unknown)"
  local pinned
  pinned="$(git -C "$SCRIPT_DIR" ls-tree HEAD arti 2>/dev/null | awk '{print $3}')"
  if [ -n "$pinned" ] && [ "$head" != "$pinned" ]; then
    print_info "Arti checkout is $head; this repository pins $pinned."
  else
    print_success "Arti at pinned commit $head"
  fi

  # Both trees, keeping their relative layout: the wrapper reaches Arti through
  # ../arti/crates/..., so copying only the wrapper would not build.
  print_info "Copying sources to $BUILD_ROOT/src ..."
  mkdir -p "$BUILD_ROOT/src"
  rm -rf "$BUILD_ROOT/src/arti-desktop-wrapper"
  cp -a "$WRAPPER_DIR" "$BUILD_ROOT/src/arti-desktop-wrapper"
  if [ ! -d "$BUILD_ROOT/src/arti" ] || [ "$CLEAN_BUILD" = true ]; then
    rm -rf "$BUILD_ROOT/src/arti"
    cp -a "$ARTI_SOURCE_DIR" "$BUILD_ROOT/src/arti"
  fi
  print_success "Sources ready"
  echo ""
}

build_desktop() {
  print_header "Building for $PLATFORM"

  mkdir -p "$OUTPUT_DIR"
  cd "$BUILD_ROOT/src/arti-desktop-wrapper"

  # --locked: build the dependency versions this tree was pinned to, rather than silently
  # resolving newer ones. For a library that carries anonymity this is not a nicety.
  # -j is capped because a full Arti build will otherwise take every core and several GB of RAM.
  print_info "cargo build --locked --release -j$JOBS (this takes a while)"
  local started=$SECONDS
  /usr/bin/time -v cargo build --locked --release -j"$JOBS" 2>"$BUILD_ROOT/build-time.log" || {
    print_error "Build failed. Last lines of the log:"
    tail -30 "$BUILD_ROOT/build-time.log" >&2
    exit 1
  }
  local elapsed=$(( SECONDS - started ))

  local peak_kb
  peak_kb="$(awk '/Maximum resident set size/ {print $NF}' "$BUILD_ROOT/build-time.log" 2>/dev/null)"
  print_success "Compiled in ${elapsed}s, peak RSS ${peak_kb:-unknown} KB"

  local LIB_SRC="$BUILD_ROOT/src/arti-desktop-wrapper/target/release/libarti_desktop.$LIB_EXT"
  if [ ! -f "$LIB_SRC" ]; then
    print_error "Build reported success but $LIB_SRC is missing"
    exit 1
  fi

  local LIB_OUT="$OUTPUT_DIR/libarti_desktop.$LIB_EXT"
  cp "$LIB_SRC" "$LIB_OUT"
  print_success "Built: $LIB_OUT ($(du -m "$LIB_OUT" | cut -f1)MB)"

  verify_exports "$LIB_OUT"
  install_if_requested "$LIB_OUT"
}

# The JVM resolves these by name at load time, so a library missing one fails at first use rather
# than at build time. Checking here turns that into a build failure.
verify_exports() {
  local lib="$1"
  command -v readelf >/dev/null 2>&1 || { print_info "readelf unavailable, skipping export check"; return; }

  local missing=0
  local symbols
  symbols="$(readelf --dyn-syms --wide "$lib" 2>/dev/null || true)"
  for sym in $(grep -oE 'Java_[A-Za-z0-9_]+' "$SCRIPT_DIR/arti-desktop-wrapper/src/lib.rs" | sort -u); do
    if ! grep -q "\b$sym\b" <<<"$symbols"; then
      print_error "Exported symbol missing from the library: $sym"
      missing=1
    fi
  done
  [ "$missing" -eq 0 ] && print_success "All JNI entry points are exported"
  [ "$missing" -eq 0 ] || exit 1
}

install_if_requested() {
  local lib="$1"
  if [ "$INSTALL_LIB" != true ]; then
    echo ""
    print_info "NOT installed. The app will not load it from here."
    print_info "Panics unwind and are caught at every JNI export, so a panic inside Arti now"
    print_info "returns an error rather than aborting the JVM. That narrows the blast radius; it"
    print_info "does not make the JVM immortal -- allocation failure, stack overflow and an"
    print_info "explicit abort still escape it."
    print_info "Re-run with --install when you want it live (or copy it to $LIBS_DIR)."
    return
  fi

  mkdir -p "$LIBS_DIR"
  cp "$lib" "$LIBS_DIR/"
  print_success "Installed into $LIBS_DIR -- the next desktop run will load it"
}

# ==============================================================================
# Main
# ==============================================================================

check_prerequisites
prepare_sources
build_desktop

print_header "Build Complete"
print_success "Library built in: $OUTPUT_DIR"

echo ""
ls -lh "$OUTPUT_DIR"/*

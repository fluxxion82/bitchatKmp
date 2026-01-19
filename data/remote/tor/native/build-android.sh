#!/usr/bin/env bash
#
# Build Arti native libraries for Android
#
# Requirements:
#   - Bash 4+ (macOS: brew install bash)
#   - Rust toolchain with Android targets:
#       rustup target add aarch64-linux-android x86_64-linux-android
#   - cargo-ndk: cargo install cargo-ndk
#   - Android NDK 25+ (for 16KB page size support)
#
# Usage:
#   ./build-android.sh              # Build both architectures
#   ./build-android.sh --release    # Build ARM64 only (production)
#   ./build-android.sh --clean      # Clean and rebuild

set -euo pipefail

# ==============================================================================
# Configuration
# ==============================================================================

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Check Bash version BEFORE using any Bash 4+ features (like associative arrays)
if [ "${BASH_VERSINFO:-0}" -lt 4 ]; then
  echo -e "${RED}Error: Bash 4+ required for associative arrays${NC}"
  echo "Current version: $BASH_VERSION"
  echo ""
  echo "Options:"
  echo "  1. Install Bash 4+ via Homebrew:"
  echo "     brew install bash"
  echo ""
  echo "  2. Run with Homebrew's bash explicitly:"
  echo "     /usr/local/bin/bash ./build-android.sh"
  echo "     # Or if using Apple Silicon:"
  echo "     /opt/homebrew/bin/bash ./build-android.sh"
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ARTI_SOURCE_DIR="$SCRIPT_DIR/arti"
WRAPPER_DIR="$SCRIPT_DIR/arti-android-wrapper"
JNILIBS_DIR="$(cd "$SCRIPT_DIR/../jniLibs" && pwd)"

# Read pinned version
VERSION="$(tr -d '[:space:]' < "$SCRIPT_DIR/ARTI_VERSION")"

# Auto-detect NDK
detect_ndk() {
  local candidates=(
    "$HOME/Library/Android/sdk/ndk/27.0.12077973"
    "$HOME/Library/Android/sdk/ndk"
    "$HOME/Android/Sdk/ndk/27.0.12077973"
    "$HOME/Android/Sdk/ndk"
  )
  for candidate in "${candidates[@]}"; do
    if [ -d "$candidate" ]; then
      echo "$candidate"
      return
    fi
  done
  local base="$HOME/Library/Android/sdk/ndk"
  if [ -d "$base" ]; then
    find "$base" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort | tail -1
  fi
}

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  ANDROID_NDK_HOME="$(detect_ndk)"
fi
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  echo -e "${RED}Error: ANDROID_NDK_HOME not set${NC}"
  exit 1
fi
export ANDROID_NDK_HOME

MIN_SDK_VERSION=26
RELEASE_ONLY=false
CLEAN_BUILD=false

# Parse arguments
while [[ $# -gt 0 ]]; do
  case "$1" in
    --release) RELEASE_ONLY=true; shift ;;
    --clean) CLEAN_BUILD=true; shift ;;
    --help|-h)
      echo "Usage: $0 [--release] [--clean]"
      exit 0
      ;;
    *)
      echo -e "${RED}Error: Unknown argument: $1${NC}"
      exit 1
      ;;
  esac
done

# Architectures
if [ "$RELEASE_ONLY" = true ]; then
  TARGETS=("aarch64-linux-android")
else
  TARGETS=("aarch64-linux-android" "x86_64-linux-android")
fi

declare -A ABI_MAP=(
  ["aarch64-linux-android"]="arm64-v8a"
  ["x86_64-linux-android"]="x86_64"
)

# ==============================================================================
# Functions
# ==============================================================================

print_header() { echo -e "${BLUE}=========================================${NC}\n${BLUE}$1${NC}\n${BLUE}=========================================${NC}"; }
print_success() { echo -e "${GREEN}✓ $1${NC}"; }
print_error() { echo -e "${RED}✗ $1${NC}"; }
print_info() { echo -e "${YELLOW}ℹ $1${NC}"; }

check_prerequisites() {
  print_header "Checking Prerequisites"

  print_success "Bash $BASH_VERSION"

  if ! command -v git >/dev/null 2>&1; then
    print_error "git not installed"
    exit 1
  fi
  print_success "git $(git --version | cut -d' ' -f3)"

  if ! command -v rustc >/dev/null 2>&1; then
    print_error "Rust not installed (https://rustup.rs/)"
    exit 1
  fi
  print_success "Rust $(rustc --version | cut -d' ' -f2)"

  if ! command -v cargo-ndk >/dev/null 2>&1; then
    print_error "cargo-ndk not installed (cargo install cargo-ndk)"
    exit 1
  fi
  print_success "cargo-ndk installed"

  if [ ! -d "$ANDROID_NDK_HOME" ]; then
    print_error "NDK not found: $ANDROID_NDK_HOME"
    exit 1
  fi
  print_success "NDK: $ANDROID_NDK_HOME"

  for TARGET in "${TARGETS[@]}"; do
    if ! rustup target list --installed | grep -qx "$TARGET"; then
      print_error "Target $TARGET not installed (rustup target add $TARGET)"
      exit 1
    fi
  done
  print_success "All Rust targets installed"

  echo ""
}

clone_arti() {
  print_header "Cloning Arti $VERSION"

  if [ "$CLEAN_BUILD" = true ] && [ -d "$ARTI_SOURCE_DIR" ]; then
    print_info "Removing existing Arti source..."
    rm -rf "$ARTI_SOURCE_DIR"
  fi

  if [ -d "$ARTI_SOURCE_DIR/.git" ]; then
    print_info "Arti already cloned, updating..."
    cd "$ARTI_SOURCE_DIR"
    git fetch --tags
  else
    print_info "Cloning Arti from GitLab..."
    git clone https://gitlab.torproject.org/tpo/core/arti.git "$ARTI_SOURCE_DIR"
    cd "$ARTI_SOURCE_DIR"
  fi

  print_info "Checking out $VERSION..."
  git checkout "$VERSION"
  print_success "Arti $VERSION ready"
  echo ""
}

build_target() {
  local TARGET="$1"
  local ABI="${ABI_MAP[$TARGET]}"
  local OUTPUT_DIR="$JNILIBS_DIR/$ABI"

  print_header "Building for $TARGET ($ABI)"

  mkdir -p "$OUTPUT_DIR"

  cd "$WRAPPER_DIR"

  print_info "Running cargo ndk..."
  cargo ndk \
    -t "$ABI" \
    --platform "$MIN_SDK_VERSION" \
    -o "$JNILIBS_DIR" \
    build --release

  local SO_FILE="$OUTPUT_DIR/libarti_android.so"
  if [ ! -f "$SO_FILE" ]; then
    print_error "Build failed: $SO_FILE not found"
    exit 1
  fi

  local SIZE_MB=$(du -m "$SO_FILE" | cut -f1)
  print_success "Built: $SO_FILE (${SIZE_MB}MB)"

  # Strip debug symbols
  if command -v llvm-strip >/dev/null 2>&1; then
    print_info "Stripping debug symbols..."
    llvm-strip --strip-debug "$SO_FILE"
    local NEW_SIZE_MB=$(du -m "$SO_FILE" | cut -f1)
    print_success "Stripped: ${NEW_SIZE_MB}MB"
  fi

  # Verify JNI symbols
  if command -v nm >/dev/null 2>&1; then
    print_info "Verifying JNI symbols..."

    local REQUIRED_SYMBOLS=(
      "Java_com_bitchat_tor_TorManager_nativeGetVersion"
      "Java_com_bitchat_tor_TorManager_nativeSetLogCallback"
      "Java_com_bitchat_tor_TorManager_nativeInitialize"
      "Java_com_bitchat_tor_TorManager_nativeStartSocksProxy"
      "Java_com_bitchat_tor_TorManager_nativeStop"
    )

    local MISSING=0
    for SYM in "${REQUIRED_SYMBOLS[@]}"; do
      if ! nm -gD "$SO_FILE" 2>/dev/null | grep -q "$SYM"; then
        print_error "Missing symbol: $SYM"
        MISSING=$((MISSING + 1))
      fi
    done

    if [ $MISSING -eq 0 ]; then
      print_success "All JNI symbols present"
    else
      print_error "$MISSING required symbols missing"
      exit 1
    fi
  fi

  # Verify 16KB page alignment
  if command -v readelf >/dev/null 2>&1 || command -v llvm-readelf >/dev/null 2>&1; then
    print_info "Verifying 16KB page alignment..."
    local READELF_CMD="llvm-readelf"
    if ! command -v llvm-readelf >/dev/null 2>&1; then
      READELF_CMD="readelf"
    fi

    if $READELF_CMD -l "$SO_FILE" 2>/dev/null | grep -q "LOAD.*0x4000"; then
      print_success "16KB page alignment verified"
    else
      print_info "Note: 16KB alignment not detected (may be fine)"
    fi
  fi

  echo ""
}

# ==============================================================================
# Main
# ==============================================================================

check_prerequisites
clone_arti

for TARGET in "${TARGETS[@]}"; do
  build_target "$TARGET"
done

print_header "Build Complete"
print_success "Libraries built in: $JNILIBS_DIR"

if [ "$RELEASE_ONLY" = false ]; then
  print_info "Debug build includes x86_64 for emulator testing"
  print_info "For production, rebuild with: $0 --release"
fi

echo ""
ls -lh "$JNILIBS_DIR"/*/*.so

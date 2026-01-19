#!/usr/bin/env bash
#
# Build Arti static library for macOS (Kotlin/Native)
#
# Requirements:
#   - Xcode command line tools (xcode-select --install)
#   - Rust toolchain with macOS targets:
#       rustup target add aarch64-apple-darwin x86_64-apple-darwin
#
# Usage:
#   ./build-macos.sh              # Build all architectures
#   ./build-macos.sh --clean      # Clean and rebuild

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
# Reuse iOS wrapper - the C FFI is platform-agnostic
WRAPPER_DIR="$SCRIPT_DIR/arti-ios-wrapper"
LIBS_DIR="$SCRIPT_DIR/libs"

# Read pinned version
VERSION="$(tr -d '[:space:]' < "$SCRIPT_DIR/ARTI_VERSION")"

MACOS_MIN=11.0
CLEAN_BUILD=false

# Parse arguments
while [[ $# -gt 0 ]]; do
  case "$1" in
    --clean) CLEAN_BUILD=true; shift ;;
    --help|-h)
      echo "Usage: $0 [--clean]"
      exit 0
      ;;
    *)
      echo -e "${RED}Error: Unknown argument: $1${NC}"
      exit 1
      ;;
  esac
done

# ==============================================================================
# Functions
# ==============================================================================

print_header() { echo -e "${BLUE}=========================================${NC}\n${BLUE}$1${NC}\n${BLUE}=========================================${NC}"; }
print_success() { echo -e "${GREEN}✓ $1${NC}"; }
print_error() { echo -e "${RED}✗ $1${NC}"; }
print_info() { echo -e "${YELLOW}ℹ $1${NC}"; }

check_prerequisites() {
  print_header "Checking Prerequisites"

  if ! command -v xcrun >/dev/null 2>&1; then
    print_error "Xcode command line tools not installed (xcode-select --install)"
    exit 1
  fi
  print_success "Xcode tools installed"

  if ! command -v rustc >/dev/null 2>&1; then
    print_error "Rust not installed (https://rustup.rs/)"
    exit 1
  fi
  print_success "Rust $(rustc --version | cut -d' ' -f2)"

  local TARGETS=("aarch64-apple-darwin" "x86_64-apple-darwin")
  for TARGET in "${TARGETS[@]}"; do
    if ! rustup target list --installed | grep -qx "$TARGET"; then
      print_error "Target $TARGET not installed (rustup target add $TARGET)"
      exit 1
    fi
  done
  print_success "All macOS Rust targets installed"

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
  local ARCH_NAME="$2"
  local OUTPUT_DIR="$LIBS_DIR/$ARCH_NAME/lib"

  print_header "Building for $TARGET ($ARCH_NAME)"

  mkdir -p "$OUTPUT_DIR"

  cd "$WRAPPER_DIR"

  # Set macOS deployment target
  export MACOSX_DEPLOYMENT_TARGET="$MACOS_MIN"

  print_info "Running cargo build..."
  cargo build --release --target "$TARGET"

  local LIB_SRC="$WRAPPER_DIR/target/$TARGET/release/libarti_ios.a"
  local LIB_DST="$OUTPUT_DIR/libarti_macos.a"

  if [ ! -f "$LIB_SRC" ]; then
    print_error "Build failed: $LIB_SRC not found"
    exit 1
  fi

  cp "$LIB_SRC" "$LIB_DST"

  local SIZE_MB=$(du -m "$LIB_DST" | cut -f1)
  print_success "Built: $LIB_DST (${SIZE_MB}MB)"

  echo ""
}

# Also copy header file for cinterop
copy_header() {
  print_header "Copying Header File"

  local HEADER_SRC="$WRAPPER_DIR/arti_ios.h"
  local HEADER_DST="$WRAPPER_DIR/arti_macos.h"

  if [ ! -f "$HEADER_DST" ] || [ "$HEADER_SRC" -nt "$HEADER_DST" ]; then
    # Create macOS header (same content, different guard name for clarity)
    sed 's/ARTI_IOS_H/ARTI_MACOS_H/g' "$HEADER_SRC" > "$HEADER_DST"
    print_success "Created: $HEADER_DST"
  else
    print_info "Header already exists: $HEADER_DST"
  fi

  echo ""
}

# ==============================================================================
# Main
# ==============================================================================

check_prerequisites
clone_arti
copy_header

# Build for all macOS architectures
build_target "aarch64-apple-darwin" "macos-arm64"
build_target "x86_64-apple-darwin" "macos-x64"

print_header "Build Complete"
print_success "Libraries built in: $LIBS_DIR"

echo ""
echo "Built libraries:"
ls -lh "$LIBS_DIR"/macos-*/lib/*.a 2>/dev/null || echo "No macOS libraries found"

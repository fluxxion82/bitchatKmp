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

# Read pinned version
VERSION="$(tr -d '[:space:]' < "$SCRIPT_DIR/ARTI_VERSION")"

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

build_desktop() {
  print_header "Building for $PLATFORM"

  mkdir -p "$LIBS_DIR"

  cd "$WRAPPER_DIR"

  print_info "Running cargo build..."
  cargo build --release

  # Find the built library
  local LIB_SRC="$WRAPPER_DIR/target/release/libarti_desktop.$LIB_EXT"
  local LIB_DST="$LIBS_DIR/libarti_desktop.$LIB_EXT"

  if [ ! -f "$LIB_SRC" ]; then
    print_error "Build failed: $LIB_SRC not found"
    exit 1
  fi

  cp "$LIB_SRC" "$LIB_DST"

  local SIZE_MB=$(du -m "$LIB_DST" | cut -f1)
  print_success "Built: $LIB_DST (${SIZE_MB}MB)"

  echo ""
}

# ==============================================================================
# Main
# ==============================================================================

check_prerequisites
clone_arti
build_desktop

print_header "Build Complete"
print_success "Library built in: $LIBS_DIR"

echo ""
ls -lh "$LIBS_DIR"/*

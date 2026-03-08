#!/usr/bin/env bash
#
# Cross-compile Arti static library for linuxArm64 (Raspberry Pi / embedded ARM64)
#
# This script is designed to run INSIDE the bitchat-linux-arm64-cross Docker container.
# Do NOT run this script directly on your host machine!
#
# =============================================================================
# USAGE
# =============================================================================
#
# Use the master build script (recommended):
#   cd bitchatKmp
#   ./scripts/build-native-linux-arm64.sh
#
# Or build just Arti via Docker manually:
#   docker run --platform linux/amd64 --rm \
#     -v $(pwd)/data/remote/tor/native:/build \
#     bitchat-linux-arm64-cross \
#     bash /build/build-linux-arm64.sh
#
# =============================================================================
# PREREQUISITES (provided by Docker image)
# =============================================================================
#
# - ARM64 cross-compiler (aarch64-linux-gnu-gcc)
# - Rust toolchain with aarch64-unknown-linux-gnu target
# - Cargo configured for cross-compilation
#
# =============================================================================
# BUILD TIME WARNING
# =============================================================================
#
# Arti is a large Rust project. First build takes 15-30 minutes.
# Subsequent builds are faster due to incremental compilation.
#
# =============================================================================

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
ARTI_SOURCE_DIR="${SCRIPT_DIR}/arti"
WRAPPER_DIR="${SCRIPT_DIR}/arti-linux-wrapper"
LIBS_DIR="${SCRIPT_DIR}/libs/linux-arm64/lib"

# Read pinned version from ARTI_VERSION file
VERSION="$(tr -d '[:space:]' < "${SCRIPT_DIR}/ARTI_VERSION")"

TARGET="aarch64-unknown-linux-gnu"
CLEAN_BUILD=false

# Parse arguments
while [[ $# -gt 0 ]]; do
  case "$1" in
    --clean) CLEAN_BUILD=true; shift ;;
    --help|-h)
      echo "Usage: $0 [--clean]"
      echo ""
      echo "Options:"
      echo "  --clean    Remove existing Arti source and rebuild from scratch"
      echo "  --help     Show this help message"
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

  # Check if running inside Docker with cross-compiler
  if ! command -v aarch64-linux-gnu-gcc &> /dev/null; then
    print_error "Cross-compiler not found!"
    echo ""
    echo "This script must be run inside the Docker container."
    echo "Do NOT run it directly on your host machine."
    echo ""
    echo "Use the master build script instead:"
    echo "  ./scripts/build-native-linux-arm64.sh"
    echo ""
    exit 1
  fi
  print_success "ARM64 cross-compiler available"

  # Check Rust
  if ! command -v rustc &> /dev/null; then
    print_error "Rust not installed in container"
    exit 1
  fi
  print_success "Rust $(rustc --version | cut -d' ' -f2)"

  # Check Cargo
  if ! command -v cargo &> /dev/null; then
    print_error "Cargo not installed in container"
    exit 1
  fi
  print_success "Cargo available"

  # Check target is installed
  if ! rustup target list --installed | grep -qx "$TARGET"; then
    print_info "Adding Rust target $TARGET..."
    rustup target add "$TARGET"
  fi
  print_success "Rust target $TARGET installed"

  # Verify wrapper directory exists
  if [ ! -d "$WRAPPER_DIR" ]; then
    print_error "Wrapper directory not found: $WRAPPER_DIR"
    exit 1
  fi
  print_success "Wrapper directory exists"

  echo ""
}

clone_arti() {
  print_header "Cloning Arti $VERSION"

  if [ "$CLEAN_BUILD" = true ] && [ -d "$ARTI_SOURCE_DIR" ]; then
    print_info "Removing existing Arti source (--clean)..."
    rm -rf "$ARTI_SOURCE_DIR"
  fi

  if [ -d "$ARTI_SOURCE_DIR/.git" ]; then
    print_info "Arti already cloned, updating..."
    cd "$ARTI_SOURCE_DIR"
    git fetch --tags
  else
    print_info "Cloning Arti from GitLab (this may take a few minutes)..."
    git clone https://gitlab.torproject.org/tpo/core/arti.git "$ARTI_SOURCE_DIR"
    cd "$ARTI_SOURCE_DIR"
  fi

  print_info "Checking out $VERSION..."
  git checkout "$VERSION"
  print_success "Arti $VERSION ready"
  echo ""
}

build_arti() {
  print_header "Building Arti for $TARGET"

  mkdir -p "$LIBS_DIR"

  cd "$WRAPPER_DIR"

  print_info "Running cargo build (this may take 15-30 minutes on first build)..."
  print_info "Target: $TARGET"
  print_info "Output: $LIBS_DIR/libarti_linux.a"
  echo ""

  # Build with release profile
  cargo build --release --target "$TARGET"

  local LIB_SRC="$WRAPPER_DIR/target/$TARGET/release/libarti_linux.a"
  local LIB_DST="$LIBS_DIR/libarti_linux.a"

  if [ ! -f "$LIB_SRC" ]; then
    print_error "Build failed: $LIB_SRC not found"
    echo ""
    echo "Check the cargo build output above for errors."
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

echo ""
print_header "Arti Build for linuxArm64"
echo "Version: $VERSION"
echo "Target:  $TARGET"
echo ""

check_prerequisites
clone_arti
build_arti

print_header "Build Complete"
print_success "Arti library built successfully!"
echo ""
echo "Output files:"
ls -lh "$LIBS_DIR"/*.a
echo ""
echo "Header file:"
ls -lh "$WRAPPER_DIR"/arti_linux.h
echo ""
echo "Next steps:"
echo "  1. Compile Kotlin module:"
echo "     ./gradlew :data:remote:tor:compileKotlinLinuxArm64"
echo ""
echo "  2. Link embedded app:"
echo "     ./gradlew :apps:embedded:linkDebugExecutableLinuxArm64"
echo ""

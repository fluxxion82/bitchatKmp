#!/usr/bin/env bash
#
# Build Arti for all platforms
#
# Usage:
#   ./build-all.sh                  # Build all platforms
#   ./build-all.sh --android-only   # Build Android only
#   ./build-all.sh --ios-only       # Build iOS only
#   ./build-all.sh --desktop-only   # Build Desktop only
#   ./build-all.sh --clean          # Clean and rebuild all

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

BUILD_ANDROID=true
BUILD_IOS=true
BUILD_DESKTOP=true
CLEAN_FLAG=""

# Parse arguments
while [[ $# -gt 0 ]]; do
  case "$1" in
    --android-only)
      BUILD_IOS=false
      BUILD_DESKTOP=false
      shift
      ;;
    --ios-only)
      BUILD_ANDROID=false
      BUILD_DESKTOP=false
      shift
      ;;
    --desktop-only)
      BUILD_ANDROID=false
      BUILD_IOS=false
      shift
      ;;
    --clean)
      CLEAN_FLAG="--clean"
      shift
      ;;
    --help|-h)
      echo "Usage: $0 [OPTIONS]"
      echo ""
      echo "Options:"
      echo "  --android-only   Build Android only"
      echo "  --ios-only       Build iOS only"
      echo "  --desktop-only   Build Desktop only"
      echo "  --clean          Clean and rebuild"
      echo ""
      exit 0
      ;;
    *)
      echo -e "${RED}Error: Unknown argument: $1${NC}"
      echo "Run: $0 --help"
      exit 1
      ;;
  esac
done

# ==============================================================================
# Functions
# ==============================================================================

print_header() { echo -e "\n${BLUE}=========================================${NC}\n${BLUE}$1${NC}\n${BLUE}=========================================${NC}\n"; }
print_success() { echo -e "${GREEN}✓ $1${NC}"; }
print_error() { echo -e "${RED}✗ $1${NC}"; }

run_build() {
  local PLATFORM="$1"
  local SCRIPT="$2"

  print_header "Building $PLATFORM"

  if [ ! -f "$SCRIPT" ]; then
    print_error "Build script not found: $SCRIPT"
    exit 1
  fi

  chmod +x "$SCRIPT"

  if ! "$SCRIPT" $CLEAN_FLAG; then
    print_error "$PLATFORM build failed"
    exit 1
  fi

  print_success "$PLATFORM build complete"
}

# ==============================================================================
# Main
# ==============================================================================

print_header "Arti Build Orchestrator"

echo "Building platforms:"
[ "$BUILD_ANDROID" = true ] && echo "  • Android"
[ "$BUILD_IOS" = true ] && echo "  • iOS"
[ "$BUILD_DESKTOP" = true ] && echo "  • Desktop"
echo ""

if [ "$BUILD_ANDROID" = true ]; then
  run_build "Android" "$SCRIPT_DIR/build-android.sh"
fi

if [ "$BUILD_IOS" = true ]; then
  run_build "iOS" "$SCRIPT_DIR/build-ios.sh"
fi

if [ "$BUILD_DESKTOP" = true ]; then
  run_build "Desktop" "$SCRIPT_DIR/build-desktop.sh"
fi

print_header "All Builds Complete"

echo "Output locations:"
[ "$BUILD_ANDROID" = true ] && echo "  Android: $(cd "$SCRIPT_DIR/../../jniLibs" && pwd)"
[ "$BUILD_IOS" = true ] && echo "  iOS:     $SCRIPT_DIR/libs/ios-*"
[ "$BUILD_DESKTOP" = true ] && echo "  Desktop: $SCRIPT_DIR/libs/desktop"
echo ""

#!/usr/bin/env bash
set -euo pipefail

# Master build script for cross-compiling all native libraries for linuxArm64
#
# This script builds:
# - libsodium (cryptographic primitives)
# - secp256k1 (elliptic curve crypto for Bitcoin/Nostr)
# - noise-c (Noise Protocol implementation)
# - gattlib (BLE GATT client library for BlueZ)
# - arti (Tor client - Rust implementation)
#
# Prerequisites:
# - Docker must be installed and running
# - The Docker image will be built automatically if not present
#
# Usage:
#   ./scripts/build-native-linux-arm64.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${SCRIPT_DIR}/.."

DOCKER_IMAGE="bitchat-linux-arm64-cross"
RUST_TOOLCHAIN="$(tr -d '[:space:]' < "${PROJECT_ROOT}/data/remote/tor/native/RUST_TOOLCHAIN")"

echo "=========================================="
echo "Native Library Cross-Compilation for linuxArm64"
echo "=========================================="
echo

# Check if Docker is available
if ! command -v docker &> /dev/null; then
    echo "ERROR: Docker is not installed or not in PATH"
    exit 1
fi

# Check if Docker daemon is running
if ! docker info &> /dev/null; then
    echo "ERROR: Docker daemon is not running"
    exit 1
fi

# Platform flag required because the ARM cross-compiler is an x86_64 binary
DOCKER_PLATFORM="linux/amd64"

echo "==> Step 1: Building Docker image (if needed)..."
if docker image inspect "${DOCKER_IMAGE}" &> /dev/null &&
   docker run --platform "${DOCKER_PLATFORM}" --rm "${DOCKER_IMAGE}" rustc --version | grep -q "rustc ${RUST_TOOLCHAIN} "; then
    echo "    Docker image '${DOCKER_IMAGE}' already exists, skipping build"
else
    echo "    Building Docker image '${DOCKER_IMAGE}' (platform: ${DOCKER_PLATFORM})..."
    docker build --platform "${DOCKER_PLATFORM}" --build-arg "RUST_TOOLCHAIN=${RUST_TOOLCHAIN}" -t "${DOCKER_IMAGE}" -f "${PROJECT_ROOT}/docker/Dockerfile.linux-arm64-cross" "${PROJECT_ROOT}"
    echo "    Docker image built successfully"
fi
echo

echo "==> Step 2: Building libsodium..."
docker run --platform "${DOCKER_PLATFORM}" --rm \
    -v "${PROJECT_ROOT}/data/crypto/native:/build" \
    "${DOCKER_IMAGE}" \
    bash /build/build-libsodium-linux-arm64.sh
echo "    libsodium built successfully"
echo

echo "==> Step 3: Building secp256k1..."
docker run --platform "${DOCKER_PLATFORM}" --rm \
    -v "${PROJECT_ROOT}/data/crypto/native:/build" \
    "${DOCKER_IMAGE}" \
    bash /build/build-secp256k1-linux-arm64.sh
echo "    secp256k1 built successfully"
echo

echo "==> Step 4: Building noise-c (with libsodium)..."
# Mount both noise and crypto directories so noise-c can link against libsodium
docker run --platform "${DOCKER_PLATFORM}" --rm \
    -v "${PROJECT_ROOT}/data/noise/native:/build" \
    -v "${PROJECT_ROOT}/data/crypto/native:/crypto" \
    "${DOCKER_IMAGE}" \
    bash /build/build-linux-arm64.sh
echo "    noise-c built successfully"
echo

echo "==> Step 5: Building GattLib (BLE GATT client)..."
# Check if gattlib submodule is initialized
if [ ! -f "${PROJECT_ROOT}/data/remote/transport/bluetooth/native/gattlib/CMakeLists.txt" ]; then
    echo "    GattLib submodule not found. Initializing..."
    git -C "${PROJECT_ROOT}" submodule update --init data/remote/transport/bluetooth/native/gattlib
fi
docker run --platform "${DOCKER_PLATFORM}" --rm \
    -v "${PROJECT_ROOT}/data/remote/transport/bluetooth/native:/build" \
    "${DOCKER_IMAGE}" \
    bash /build/build-gattlib-linux-arm64.sh
echo "    GattLib built successfully"
echo

echo "==> Step 6: Building Arti (Tor client)..."
echo "    NOTE: First build takes 15-30 minutes (large Rust codebase)"
# Build Arti natively on ARM64: its Rust build crashes under amd64 QEMU on Apple Silicon.
# Export only the reviewed Tor sources, with a self-contained shallow Git checkout.
# Host submodule gitdir pointers otherwise point outside a narrow container mount.
ARTI_NATIVE_DIR="${PROJECT_ROOT}/data/remote/tor/native"
source "$ARTI_NATIVE_DIR/build-common.sh"
ensure_arti_source "$ARTI_NATIVE_DIR/arti" "$(tr -d '[:space:]' < "$ARTI_NATIVE_DIR/ARTI_VERSION")" || exit 1
ARTI_DOCKER_SOURCE="$(mktemp -d)"
trap 'rm -rf "$ARTI_DOCKER_SOURCE"' EXIT
git clone --quiet --no-local --depth 1 "$ARTI_NATIVE_DIR/arti" "$ARTI_DOCKER_SOURCE/arti"
mkdir -p "$ARTI_DOCKER_SOURCE/arti-linux-wrapper"
cp "$ARTI_NATIVE_DIR"/{build-common.sh,build-linux-arm64.sh,ARTI_VERSION,RUST_TOOLCHAIN} "$ARTI_DOCKER_SOURCE/"
cp "$ARTI_NATIVE_DIR/arti-linux-wrapper"/{Cargo.toml,Cargo.lock,rust-toolchain.toml,arti_linux.h} "$ARTI_DOCKER_SOURCE/arti-linux-wrapper/"
cp -R "$ARTI_NATIVE_DIR/arti-linux-wrapper/src" "$ARTI_DOCKER_SOURCE/arti-linux-wrapper/"
cp -R "$ARTI_NATIVE_DIR/arti-lifecycle" "$ARTI_DOCKER_SOURCE/"
ARTI_DOCKER_PLATFORM=linux/arm64
ARTI_DOCKER_IMAGE="rust:${RUST_TOOLCHAIN}-bookworm"
case "$(uname -m)" in
    arm64|aarch64) ;;
    *) ARTI_DOCKER_PLATFORM="${DOCKER_PLATFORM}"; ARTI_DOCKER_IMAGE="${DOCKER_IMAGE}" ;;
esac
docker run --platform "${ARTI_DOCKER_PLATFORM}" --rm \
    -v "${ARTI_DOCKER_SOURCE}:/build" \
    -v bitchat-cargo-registry:/usr/local/cargo/registry \
    -v bitchat-arti-arm64-target:/build/arti-linux-wrapper/target \
    "${ARTI_DOCKER_IMAGE}" \
    bash /build/build-linux-arm64.sh
mkdir -p "$ARTI_NATIVE_DIR/libs/linux-arm64/lib"
cp "$ARTI_DOCKER_SOURCE/libs/linux-arm64/lib/libarti_linux.a" "$ARTI_NATIVE_DIR/libs/linux-arm64/lib/"
echo "    Arti built successfully"
echo

echo "=========================================="
echo "All native libraries built successfully!"
echo "=========================================="
echo
echo "Output locations:"
echo "  libsodium:  data/crypto/native/libsodium/build/linux-arm64/lib/libsodium.a"
echo "  secp256k1:  data/crypto/native/secp256k1/build/linux-arm64/lib/libsecp256k1.a"
echo "  noise-c:    data/noise/native/noise-c/build/linux-arm64/lib/"
echo "  gattlib:    data/remote/transport/bluetooth/native/gattlib/build/linux-arm64/install/lib/"
echo "  arti:       data/remote/tor/native/libs/linux-arm64/lib/libarti_linux.a"
echo
echo "Next steps:"
echo "  1. Compile Kotlin modules:"
echo "     ./gradlew -Pembedded.enabled=true :data:crypto:compileKotlinLinuxArm64"
echo "     ./gradlew -Pembedded.enabled=true :data:noise:compileKotlinLinuxArm64"
echo "     ./gradlew -Pembedded.enabled=true :data:remote:transport:bluetooth:compileKotlinLinuxArm64"
echo "     ./gradlew -Pembedded.enabled=true :data:remote:tor:compileKotlinLinuxArm64"
echo
echo "  2. Link embedded app:"
echo "     ./gradlew -Pembedded.enabled=true :apps:embedded:compose:linkDebugExecutableLinuxArm64"
echo
echo "  3. Test on Raspberry Pi:"
echo "     scp apps/embedded/compose/build/bin/linuxArm64/debugExecutable/* pi:/tmp/"
echo "     ssh pi 'sudo /tmp/bitchat-embedded.kexe'"

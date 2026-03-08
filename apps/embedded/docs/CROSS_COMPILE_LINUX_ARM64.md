# Cross-Compiling Native Libraries for linuxArm64

This document covers the process of cross-compiling native C libraries (libsodium, secp256k1, noise-c) for the linuxArm64 target, enabling full native crypto support in the embedded app.

## Overview

The embedded app (`apps/embedded`) targets linuxArm64 for deployment on Raspberry Pi and similar ARM64 Linux devices. The crypto and noise modules require native C libraries that must be cross-compiled from macOS.

## Prerequisites

- Docker Desktop installed and running
- On Apple Silicon Macs, Docker will use QEMU emulation for x86_64 containers

## Quick Start

Run the master build script:

```bash
./scripts/build-native-linux-arm64.sh
```

This builds:
1. Docker cross-compilation image
2. libsodium (cryptographic primitives)
3. secp256k1 (elliptic curve crypto for Bitcoin/Nostr)
4. noise-c (Noise Protocol implementation)
5. GattLib (BLE GATT client library for BlueZ)

Then build the embedded app:

```bash
./gradlew :apps:embedded:linkDebugExecutableLinuxArm64
```

## Docker Image Details

### Why linux/amd64 Platform?

The cross-compiler (`gcc-aarch64-linux-gnu`) is an **x86_64 binary**. On Apple Silicon Macs, this means:

- The Docker container must run as `linux/amd64`
- Docker uses QEMU to emulate x86_64
- All docker commands require `--platform linux/amd64`

Without this flag, you'll see errors like:
```
qemu-x86_64: Could not open '/lib64/ld-linux-x86-64.so.2'
```

### Build the Docker Image Manually

```bash
docker build --platform linux/amd64 \
    -t bitchat-linux-arm64-cross \
    -f docker/Dockerfile.linux-arm64-cross .
```

### Rebuild Docker Image After Dockerfile Changes

If you modify `docker/Dockerfile.linux-arm64-cross`, you need to force a rebuild:

```bash
# Remove the cached image
docker rmi bitchat-linux-arm64-cross

# Rebuild (the build script will recreate it automatically)
./scripts/build-native-linux-arm64.sh
```

Alternatively, force rebuild without removing first:

```bash
docker build --platform linux/amd64 --no-cache \
    -t bitchat-linux-arm64-cross \
    -f docker/Dockerfile.linux-arm64-cross .
```

### Docker Image Contents

- Base: Ubuntu 20.04
- CMake: 3.28.3 (required for GattLib, Ubuntu 20.04 default is too old)
- Cross-compiler: `gcc-aarch64-linux-gnu` (Ubuntu's native cross-compiler package)
- Build tools: autoconf, automake, libtool, make, pkg-config
- Parser generators: bison, flex (required for noise-c's protoc tool)
- ARM64 libraries via multiarch: GLib, D-Bus, BlueZ (for linking)

### Multiarch Setup

The Docker image uses Ubuntu's multiarch support to provide real ARM64 libraries:

```dockerfile
# Enable ARM64 architecture
dpkg --add-architecture arm64

# Add ARM64 package repository (ports.ubuntu.com)
# Install ARM64 libraries alongside x86_64
apt-get install libglib2.0-dev:arm64 libdbus-1-dev:arm64 libbluetooth-dev:arm64
```

This provides:
- Real ARM64 `.a` and `.so` files in `/usr/lib/aarch64-linux-gnu/`
- Headers compatible with the target platform
- Proper linking during cross-compilation

The toolchain provides cross-compilers with the `aarch64-linux-gnu-` prefix:
- `aarch64-linux-gnu-gcc`
- `aarch64-linux-gnu-ar`
- `aarch64-linux-gnu-ranlib`
- etc.

## Building Individual Libraries

### libsodium

```bash
docker run --platform linux/amd64 --rm \
    -v "$(pwd)/data/crypto/native:/build" \
    bitchat-linux-arm64-cross \
    bash /build/build-libsodium-linux-arm64.sh
```

**Output:** `data/crypto/native/libsodium/build/linux-arm64/lib/libsodium.a`

The script:
- Downloads libsodium 1.0.20 if not present
- Configures with `--host=aarch64-linux-gnu`
- Builds static library only (`--disable-shared --enable-static`)

### secp256k1

```bash
docker run --platform linux/amd64 --rm \
    -v "$(pwd)/data/crypto/native:/build" \
    bitchat-linux-arm64-cross \
    bash /build/build-secp256k1-linux-arm64.sh
```

**Output:** `data/crypto/native/secp256k1/build/linux-arm64/lib/libsecp256k1.a`

Enabled modules:
- `--enable-module-recovery` (ECDSA recovery)
- `--enable-module-ecdh` (Diffie-Hellman)
- `--enable-module-schnorrsig` (Schnorr signatures for Nostr)
- `--enable-module-extrakeys` (x-only pubkeys)

### noise-c

```bash
docker run --platform linux/amd64 --rm \
    -v "$(pwd)/data/noise/native:/build" \
    -v "$(pwd)/data/crypto/native:/crypto" \
    bitchat-linux-arm64-cross \
    bash /build/build-linux-arm64.sh
```

**Output:**
- `data/noise/native/noise-c/build/linux-arm64/lib/libnoiseprotocol.a`
- `data/noise/native/noise-c/build/linux-arm64/lib/libnoisekeys.a`
- `data/noise/native/noise-c/build/linux-arm64/lib/libnoiseprotobufs.a`

**Important:** The noise-c build mounts **both** `/build` (noise) and `/crypto` (libsodium) directories because noise-c is configured to use libsodium for its crypto operations.

### GattLib

```bash
docker run --platform linux/amd64 --rm \
    -v "$(pwd)/data/remote/transport/bluetooth/native:/build" \
    bitchat-linux-arm64-cross \
    bash /build/build-gattlib-linux-arm64.sh
```

**Output:** `data/remote/transport/bluetooth/native/gattlib/build/linux-arm64/install/lib/libgattlib.a`

GattLib is a BLE GATT client library that uses BlueZ's D-Bus backend. It provides:
- BLE adapter scanning
- GATT service/characteristic discovery
- Read/write characteristic operations
- Notification subscriptions

The build requires ARM64 GLib, D-Bus, and BlueZ libraries, which are provided by the Docker image's multiarch setup.

## Why noise-c Uses libsodium

### The Problem: Symbol Collision

When linking the embedded app, we encountered a duplicate symbol error:

```
ld.lld: error: duplicate symbol: poly1305_init
>>> defined at libcrypto-lib-poly1305-armv8.o in libcrypto.a
>>> defined at poly1305-donna.c in libnoiseprotocol.a
```

This happened because:
1. Kotlin/Native includes OpenSSL's `libcrypto.a` which has its own Poly1305 implementation
2. noise-c's default build includes a built-in Poly1305 implementation (`poly1305-donna.c`)
3. When linking, the linker found the same symbol defined in both libraries

### The Solution: Use libsodium Backend

noise-c supports external crypto backends via configure options:

```bash
./configure --with-libsodium --without-openssl \
    libsodium_CFLAGS="-I/crypto/libsodium/build/linux-arm64/include" \
    libsodium_LIBS="-L/crypto/libsodium/build/linux-arm64/lib -lsodium"
```

This tells noise-c to:
- Use libsodium for crypto operations (Poly1305, ChaCha20, etc.)
- Skip building its internal crypto implementations
- Define `USE_LIBSODIUM=1` during compilation

**Result:** The `libnoiseprotocol.a` library shrinks from ~359KB to ~243KB because it no longer contains redundant crypto code.

### Benefits

1. **No symbol collisions** - noise-c uses libsodium's implementations
2. **Smaller binary** - no duplicate crypto code
3. **Consistent crypto** - both crypto and noise modules use the same underlying library
4. **Better tested** - libsodium is a widely-used, audited library

## Gradle Configuration

### Cinterop Setup

The `.def` files specify paths for each platform. For linuxArm64:

**libsodium.def:**
```
linkerOpts.linux_arm64 = -L/path/to/libsodium/build/linux-arm64/lib -lsodium
compilerOpts.linux_arm64 = -I/path/to/libsodium/build/linux-arm64/include
```

**noise.def:**
```
linkerOpts.linux_arm64 = -L/path/to/noise-c/build/linux-arm64/lib -lnoiseprotocol -lnoisekeys -lnoiseprotobufs
```

### build.gradle.kts

The `data/crypto/build.gradle.kts` and `data/noise/build.gradle.kts` configure cinterop for linuxArm64:

```kotlin
linuxArm64 {
    compilations.get("main").cinterops {
        create("noise") {
            defFile(project.file("src/nativeInterop/cinterop/noise.def"))
            includeDirs("native/noise-c/include")
            extraOpts("-libraryPath", libDir)
        }
    }
    binaries.all {
        linkerOpts("-L$libDir", "-lnoiseprotocol", "-lnoisekeys", "-lnoiseprotobufs")
    }
}
```

## Kotlin Implementation Notes

### crypto_pwhash String Parameter

The libsodium cinterop binding for `crypto_pwhash` expects the password as a Kotlin `String?` directly, not as a `CPointer<ByteVar>`:

```kotlin
// Correct - pass password string directly
crypto_pwhash(
    out, outlen,
    password,           // String, not CPointer
    password.length.toULong(),
    salt, opslimit, memlimit, alg
)
```

This is because the cinterop generator maps `const char *` parameters to Kotlin String for convenience.

## Troubleshooting

### Docker Build Fails with QEMU Error

```
qemu-x86_64: Could not open '/lib64/ld-linux-x86-64.so.2'
```

**Fix:** Add `--platform linux/amd64` to all docker commands.

### noise-c Build Fails with "yacc: command not found"

```
ylwrap: line 174: yacc: command not found
```

**Fix:** The Docker image must include `bison` and `flex` packages. These are required by noise-c's `tools/protoc` build.

### Linker Error: Duplicate Symbol poly1305_init

**Fix:** Build noise-c with `--with-libsodium` to use libsodium's crypto implementations instead of the built-in ones.

### Gradle cinterop Fails: Library Not Found

```
error: cannot find -lnoiseprotocol
```

**Fix:** Run the native build script first:
```bash
./scripts/build-native-linux-arm64.sh
```

## Output Locations

After successful build:

| Library | Path |
|---------|------|
| libsodium | `data/crypto/native/libsodium/build/linux-arm64/lib/libsodium.a` |
| secp256k1 | `data/crypto/native/secp256k1/build/linux-arm64/lib/libsecp256k1.a` |
| noise-c | `data/noise/native/noise-c/build/linux-arm64/lib/libnoiseprotocol.a` |
| GattLib | `data/remote/transport/bluetooth/native/gattlib/build/linux-arm64/install/lib/libgattlib.a` |
| Embedded app | `apps/embedded/build/bin/linuxArm64/debugExecutable/bitchat-embedded.kexe` |

## Deploying to Raspberry Pi

```bash
# Copy binary to Pi
scp apps/embedded/build/bin/linuxArm64/debugExecutable/bitchat-embedded.kexe pi@raspberrypi:/tmp/

# Run on Pi
ssh pi@raspberrypi 'chmod +x /tmp/bitchat-embedded.kexe && /tmp/bitchat-embedded.kexe'
```

## References

- Docker pattern based on: `forks/jake/skiko/skiko/docker/linux-amd64/Dockerfile`
- Ubuntu Multiarch: https://wiki.ubuntu.com/MultiarchSpec
- libsodium: https://github.com/jedisct1/libsodium
- secp256k1: https://github.com/bitcoin-core/secp256k1
- noise-c: https://github.com/rweather/noise-c
- GattLib: https://github.com/labapart/gattlib

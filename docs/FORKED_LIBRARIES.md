# Forked Libraries

bitchatKmp targets `linuxArm64` (Orange Pi Zero 3) which is not an upstream-supported Kotlin/Native target for Compose Multiplatform, Koin, or Skiko. Several libraries had to be forked and patched to produce `-linuxarm64` artifacts. These forks live in the `forks/` directory at the repo root (`bitchat/forks/`).

This document is the single reference for what needs to be cloned, built, and published before bitchatKmp will compile for the embedded target.

## Quick Reference

| # | Library | Fork Repo | Branch | Version | Publish | Consumed Via |
|---|---------|-----------|--------|---------|---------|--------------|
| 1 | Compose Multiplatform | [fluxxion82/compose-multiplatform](https://github.com/fluxxion82/compose-multiplatform) | `release/1.10` | `9999.0.0-SNAPSHOT` | `publishToMavenLocal` | `~/.m2` (mavenLocal) |
| 2 | Compose Multiplatform Core | [fluxxion82/compose-multiplatform-core](https://github.com/fluxxion82/compose-multiplatform-core) | `linux-1.10.0` | `9999.0.0-SNAPSHOT` | `publishToMavenLocal` | `~/.m2` (mavenLocal) |
| 3 | Koin | [fluxxion82/koin](https://github.com/fluxxion82/koin) | `sa_linux` | `4.1.2` | `publishToMavenLocal` | `~/.m2` (mavenLocal) |
| 4 | Skiko (EGL) | [JakeWharton/skiko](https://github.com/JakeWharton/skiko) | `jw-egl-0.9.37.3-port` | `0.9.37.3-SNAPSHOT` | Pre-built maven dir | `apps/embedded/maven/` |
| 5 | MeshCore | [fluxxion82/MeshCore](https://github.com/fluxxion82/MeshCore) | `orangepi-zero3-sx1276` | N/A (native binary) | Built on-device | `/usr/local/bin/meshcored` |
| 6 | Meshtastic Firmware | [fluxxion82/firmware](https://github.com/fluxxion82/firmware) | `orangepi-rfm95w` | 2.7.x (native binary) | Built on-device | `/usr/bin/meshtasticd` |

## Build Configuration

bitchatKmp wires in the forked artifacts through three layers of Gradle configuration:

### 1. Repository ordering (`settings.gradle.kts:20-25`)

```kotlin
mavenLocal()  // Forked libs published here first
maven { url = uri("${rootDir.absolutePath}/apps/embedded/maven") }  // Jake's pre-built Skiko/Compose
```

`mavenLocal()` is listed first so that forked SNAPSHOT artifacts take priority over upstream releases.

### 2. Version forcing (`build.gradle.kts:14-49`)

The root `build.gradle.kts` uses `resolutionStrategy.eachDependency` to force `9999.0.0-SNAPSHOT` for:
- `org.jetbrains.compose.ui`, `.foundation`, `.material`, `.material3`, `.animation`, `.runtime`
- `org.jetbrains.compose.components:components-resources*`
- `org.jetbrains.androidx.lifecycle`
- `org.jetbrains.androidx.savedstate`

This ensures every module in the project resolves to the forked Compose, not upstream releases.

### 3. Explicit platform artifacts (`apps/embedded/build.gradle.kts`)

The embedded module declares explicit `-linuxarm64` artifacts because Kotlin/Native can't resolve multiplatform metadata modules for unsupported targets:
- Skiko: `org.jetbrains.skiko:skiko-linuxarm64:0.9.37.3-SNAPSHOT`
- Compose UI/Foundation/Material3: `*-linuxarm64:9999.0.0-SNAPSHOT`
- Koin: `koin-core-linuxarm64:4.1.2`, `koin-compose-linuxarm64:4.1.2`, `koin-compose-viewmodel-linuxarm64:4.1.2`
- Lifecycle/Savedstate: `*-linuxarm64:9999.0.0-SNAPSHOT`

The `presentation/screens/build.gradle.kts:125` also declares `components-resources-linuxArm64` explicitly.

### Dependency Flow

```
forks/compose-multiplatform          ──┐
forks/compose-multiplatform-core     ──┤  publishToMavenLocal
forks/koin                           ──┘        │
                                                v
                                          ~/.m2/repository/
                                                │
                                                v
Pre-built Skiko artifacts                     Gradle resolves
  ──copy──> apps/embedded/maven/             ──> artifacts
                                                │
                                                v
                                    bitchatKmp embedded binary

forks/meshcore-linux    ── build on device ──> /usr/local/bin/meshcored
forks/meshtastic-firmware ── build on device ──> /usr/bin/meshtasticd
```

---

## 1. Compose Multiplatform

**What:** JetBrains Compose gradle plugin + resource loading library.

**Why:** Upstream has no `linuxArm64` resource reader. The fork adds runtime resource resolution via `/proc/self/exe` and a Gradle task to sync resources next to the executable at build time.

**Repo & Branch:** [fluxxion82/compose-multiplatform](https://github.com/fluxxion82/compose-multiplatform) `release/1.10` (2 commits ahead of upstream)

**Changes:**
- `ResourceReader.linuxArm64.kt` — resolves resources relative to the executable using `/proc/self/exe`
- `LinuxResources.kt` (new) — Gradle `Copy` task that syncs compose-resources next to the native executable
- `ComposeResources.kt` — wired in `configureSyncLinuxComposeResources()` call

See [`EMBEDDED_NOTES.md`](../apps/embedded/EMBEDDED_NOTES.md) for full patch details and the `readlink()` null-termination caveat.

**Build & Publish:**

```bash
cd forks/compose-multiplatform/gradle-plugins
./gradlew publishToMavenLocal

cd ../components
./gradlew :resources:library:compileKotlinLinuxArm64 --rerun-tasks
./gradlew :resources:library:publishLinuxArm64PublicationToMavenLocal
```

## 2. Compose Multiplatform Core

**What:** Compose UI runtime, foundation, lifecycle, and savedstate libraries.

**Why:** Upstream Compose UI does not target `linuxArm64`. This fork (based on [Thomas-Vos's Linux Compose work](https://github.com/Thomas-Vos/compose-multiplatform-core)) adds the target and includes a `Dispatchers.Main` workaround needed on Linux native.

**Repo & Branch:** [fluxxion82/compose-multiplatform-core](https://github.com/fluxxion82/compose-multiplatform-core) `linux-1.10.0` (forked from Thomas-Vos, with upstream JetBrains as `upstream` remote)

**Changes:**
- Compose UI for Linux native target
- `Dispatchers.Main` fix for linuxArm64 (no Swing/Android looper available)
- Kotlin/Compose version alignment to match bitchatKmp
- Also produces `lifecycle-*-linuxarm64` and `savedstate-linuxarm64` artifacts

**Build & Publish:**

```bash
cd forks/compose-multiplatform-core
./gradlew publishToMavenLocal
```

This publishes all Compose UI, lifecycle, and savedstate artifacts to `~/.m2/`.

## 3. Koin

**What:** Koin dependency injection framework.

**Why:** Upstream Koin has no `linuxArm64` target. The fork adds it while disabling JS/Wasm targets and aligning the Kotlin version.

**Repo & Branch:** [fluxxion82/koin](https://github.com/fluxxion82/koin) `sa_linux` (1 commit ahead of upstream `InsertKoinIO/koin`)

**Changes:**
- Added `linuxArm64()` target
- Disabled JS/Wasm targets (not needed, simplifies build)
- Aligned Kotlin to 2.2.10
- Forced `stdlib-common` resolution

**Build & Publish:**

```bash
cd forks/koin
./gradlew publishToMavenLocal
```

**Artifacts produced:** `koin-core-linuxarm64:4.1.2`, `koin-compose-linuxarm64:4.1.2`, `koin-compose-viewmodel-linuxarm64:4.1.2`

## 4. Skiko (EGL)

**What:** Skia bindings for Kotlin — the 2D rendering engine used by Compose.

**Why:** Standard Skiko uses GLX (X11) for OpenGL function loading via `glXGetProcAddress`. Headless Linux without X11 needs EGL instead. Jake Wharton's fork uses `DirectContext.makeEGL()` with `GrGLMakeEGLInterface`.

**Repo & Branch:** [JakeWharton/skiko](https://github.com/JakeWharton/skiko) `jw-egl-0.9.37.3-port`

**Changes:**
- Replaced GLX backend with EGL (`eglGetProcAddress`)
- Native libraries bundled for `linuxarm64`

**Setup (no build required — uses pre-built artifacts):**

The pre-built EGL-enabled Skiko and Compose artifacts must be placed in `apps/embedded/maven/`. This directory is gitignored and must be set up on each machine. See [embedded README Step 0](../apps/embedded/README.md) for setup details.

## 5. MeshCore

**What:** MeshCore companion firmware for LoRa mesh networking.

**Why:** Upstream `linux` support is close, but Orange Pi Zero 3 + SX1276 required additional Linux companion patches.

**Repo & Branch:** [fluxxion82/MeshCore](https://github.com/fluxxion82/MeshCore) `orangepi-zero3-sx1276` (based on `ggodlewski/MeshCore` `linux`)

**Changes (tracked in fork branch):**
- SX1276 radio support (vs. default SX1262)
- Orange Pi Zero 3 GPIO pin mappings
- SPI device configuration for `/dev/spidev1.1`

See [`MESHCORE_RUNBOOK.md`](../apps/embedded/docs/MESHCORE_RUNBOOK.md) for full patch details and pin configuration.

**Build (on-device):**

```bash
cd ~/meshcore-linux
FIRMWARE_VERSION=dev ./build.sh build-firmware linux_companion_sx1276
sudo cp out/meshcored /usr/local/bin/meshcored
```

## 6. Meshtastic Firmware

**What:** meshtasticd native firmware for Linux LoRa devices.

**Why:** Debug logging and error handling improvements for the RF95/SX1276 SPI interface on Orange Pi Zero 3.

**Repo & Branch:** [fluxxion82/firmware](https://github.com/fluxxion82/firmware) `orangepi-rfm95w`

**Changes:**
- `src/mesh/RF95Interface.cpp` — RF95 init/reconfigure hardening for Portduino
- `src/mesh/RadioLibRF95.cpp` — init sequence logging and Portduino write-failure tolerance
- `CUSTOM_CHANGES.md` — full documentation of changes, known issues (RF95 init -20, IRQ flood, invalid pointer crash), and configuration
- `orangepi/runtime-captures/` — snapshots of Pi-only runtime/dependency patches (`LinuxGPIOPin.cpp`, `SX127x.cpp`) so ad-hoc changes are not lost

See the "Need source build for patched behavior" section in [`meshtastic-orangepi-setup.md`](meshtastic-orangepi-setup.md) for build steps.

**Build (on-device):**

```bash
cd ~/firmware
source ~/meshtastic-venv/bin/activate
pio run -e native
sudo cp .pio/build/native/program /usr/bin/meshtasticd
```

---

## First-Time Setup Checklist

Follow these steps in order on a new development machine to build the embedded target:

### 1. Clone forks

```bash
cd bitchat/forks
git clone -b linux-1.10.0 https://github.com/fluxxion82/compose-multiplatform-core.git
git clone -b release/1.10 https://github.com/fluxxion82/compose-multiplatform.git
git clone -b sa_linux https://github.com/fluxxion82/koin.git
```

### 2. Build and publish compose-multiplatform-core

```bash
cd forks/compose-multiplatform-core
./gradlew publishToMavenLocal
```

This publishes Compose UI, lifecycle, and savedstate artifacts.

### 3. Build and publish compose-multiplatform

```bash
cd forks/compose-multiplatform/gradle-plugins
./gradlew publishToMavenLocal

cd ../components
./gradlew :resources:library:compileKotlinLinuxArm64 --rerun-tasks
./gradlew :resources:library:publishLinuxArm64PublicationToMavenLocal
```

### 4. Build and publish Koin

```bash
cd forks/koin
./gradlew publishToMavenLocal
```

### 5. Copy Skiko maven artifacts

Copy the pre-built EGL-enabled Skiko and Compose artifacts into `apps/embedded/maven/`. See [embedded README Step 0](../apps/embedded/README.md) for details.

### 6. Create sysroot (see [embedded README](../apps/embedded/README.md) Step 1)

### 7. Verify build

```bash
cd bitchatKmp
./gradlew :apps:embedded:linkDebugExecutableLinuxArm64
```

If this succeeds, all forked dependencies are correctly in place.

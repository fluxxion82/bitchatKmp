# Forked Libraries

bitchatKmp targets `linuxArm64` (Orange Pi Zero 3), which is not an upstream-supported Kotlin/Native target for Compose Multiplatform or Koin. Those libraries had to be forked and patched to produce `-linuxarm64` artifacts. The forks live in the `forks/` directory at the repo root (`bitchat/forks/`).

**Skiko is no longer forked.** See [Skiko: no longer forked](#skiko-no-longer-forked) below.

This document is the single reference for what needs to be cloned, built, and published before bitchatKmp will compile for the embedded target.

## Quick Reference

| # | Library | Fork Repo | Branch | Version | Publish | Consumed Via |
|---|---------|-----------|--------|---------|---------|--------------|
| 1 | Compose Multiplatform | [fluxxion82/compose-multiplatform](https://github.com/fluxxion82/compose-multiplatform) | `release/1.10` | `9999.0.0-SNAPSHOT` | `publishToMavenLocal` | `~/.m2` (mavenLocal) |
| 2 | Compose Multiplatform Core | [fluxxion82/compose-multiplatform-core](https://github.com/fluxxion82/compose-multiplatform-core) | `linux-1.10.0` | `9999.0.0-SNAPSHOT` | `publishToMavenLocal` | `~/.m2` (mavenLocal) |
| 3 | Koin | [fluxxion82/koin](https://github.com/fluxxion82/koin) | `sa_linux_4.2.2` | `4.2.2` | `publishToMavenLocal` | `~/.m2` (mavenLocal) |
| 4 | MeshCore | [fluxxion82/MeshCore](https://github.com/fluxxion82/MeshCore) | `orangepi-zero3-sx1276` | N/A (native binary) | Built on-device | `/usr/local/bin/meshcored` |
| 5 | Meshtastic Firmware | [fluxxion82/firmware](https://github.com/fluxxion82/firmware) | `orangepi-rfm95w` | 2.7.x (native binary) | Built on-device | `/usr/bin/meshtasticd` |
| 6 | gattlib | [fluxxion82/gattlib](https://github.com/fluxxion82/gattlib) | `bitchat-null-guards` | N/A (native static lib) | `scripts/build-native-linux-arm64.sh` step 5 | `native/gattlib/build/linux-arm64/install/lib/libgattlib.a` |

## Build Configuration

All three layers below are active only when the embedded profile is on (`embedded.enabled` defaults to `false` in `gradle.properties`; pass `-Pembedded.enabled=true` or set it in `~/.gradle/gradle.properties`). A plain build resolves Compose 1.11.1 and Koin 4.2.2 from Maven Central and never touches `~/.m2`. Skiko comes from Maven Central either way.

bitchatKmp wires in the forked artifacts through three layers of Gradle configuration:

### 1. Repository ordering (`settings.gradle.kts:42-45`, plus `:20-22` in `pluginManagement`)

```kotlin
if (embeddedEnabled) {
    mavenLocal()  // Forked libs published here first
}
```

With the profile on, `mavenLocal()` is listed first in `dependencyResolutionManagement` (and added to `pluginManagement`) so that forked SNAPSHOT artifacts take priority over upstream releases. Skiko no longer matches anything in `~/.m2` and falls through to `mavenCentral()`. `settings.gradle.kts` also selects the Compose Gradle plugin version there: `embedded.composeForkVersion` (`9999.0.0-SNAPSHOT`) when embedded, `1.10.0` otherwise.

### 2. Version forcing (`build.gradle.kts:26-63`)

The root `build.gradle.kts` uses `resolutionStrategy.eachDependency` (inside `if (embeddedEnabled)`) to force `embedded.composeForkVersion` (`9999.0.0-SNAPSHOT`) for:
- `org.jetbrains.compose.ui`, `.foundation`, `.material`, `.material3`, `.animation`, `.runtime`
- `org.jetbrains.compose.components:components-resources*`
- `org.jetbrains.androidx.lifecycle`
- `org.jetbrains.androidx.savedstate`

and `embedded.koinForkVersion` (`4.2.2`) for every `io.insert-koin` artifact. The fork versions are declared in `gradle.properties` (`embedded.composeForkVersion`, `embedded.koinForkVersion`), alongside the non-fork `embedded.skikoVersion`.

This ensures every module in the project resolves to the forked Compose and Koin, not upstream releases.

### 3. Explicit platform artifacts (`apps/embedded/build.gradle.kts`)

The embedded module declares explicit `-linuxarm64` artifacts because Kotlin/Native can't resolve multiplatform metadata modules for unsupported targets:
- Skiko: `org.jetbrains.skiko:skiko-linuxarm64:0.9.47` (upstream, Maven Central; `embedded.skikoVersion`)
- Compose UI/Foundation/Material3: `*-linuxarm64:9999.0.0-SNAPSHOT`
- Koin: `koin-core-linuxarm64:4.2.2`, `koin-compose-linuxarm64:4.2.2`, `koin-compose-viewmodel-linuxarm64:4.2.2`
- Lifecycle/Savedstate: `*-linuxarm64:9999.0.0-SNAPSHOT`

The `presentation/screens/build.gradle.kts:136` also declares `components-resources-linuxArm64` explicitly (embedded builds only).

### Dependency Flow

```
forks/compose-multiplatform          ──┐
forks/compose-multiplatform-core     ──┤  publishToMavenLocal
forks/koin/projects                  ──┘        │
                                                │
                                                v
                                          ~/.m2/repository/
                                                │
                                                v
                                          Gradle resolves
                                           ──> artifacts
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

**Repo & Branch:** [fluxxion82/koin](https://github.com/fluxxion82/koin) `sa_linux_4.2.2` (the `sa_linux` linuxArm64 commit cherry-picked onto upstream tag `4.2.2`, plus two fix-ups). `sa_linux` is kept as the 4.1.2 line.

**Changes:**
- Added `linuxArm64()` target
- Disabled JS/Wasm targets (not needed, simplifies build)
- Aligned Kotlin to 2.2.10
- Forced `stdlib-common` resolution

**Build & Publish:**

```bash
cd forks/koin/projects   # the Gradle root is projects/, not the repo root
./gradlew publishToMavenLocal
```

**Artifacts produced:** `koin-core-linuxarm64:4.2.2`, `koin-compose-linuxarm64:4.2.2`, `koin-compose-viewmodel-linuxarm64:4.2.2`, `koin-core-viewmodel-linuxarm64:4.2.2`. Publish those four module paths rather than the whole build: modules outside them (navigation3, koin-fu-viewmodel) still carry upstream/fork drift. The fork pins Kotlin 2.4.20 because 2.3.20 rejects `macosX64()`, which `apps/desktop` needs for its Intel-Mac BLE dylib.

## Skiko: no longer forked

**What:** Skia bindings for Kotlin — the 2D rendering engine used by Compose.

**Why it used to be forked:** Skiko was GLX-only on `linuxArm64`, loading GL functions through
`glXGetProcAddress`, which does not work on a headless Pi with no X11. A local fork of
[JakeWharton/skiko](https://github.com/JakeWharton/skiko) (`jw-egl-0.9.37.3-port`, published as
`skiko-linuxarm64:0.9.37.3-SNAPSHOT`) added a `DirectContext.makeEGL()` API and swapped in a newer
Skia prebuilt that had EGL compiled in.

**Why it no longer is:** upstream fixed the underlying problem. From `skiko-linuxarm64` **0.9.47**
onward (JetBrains/skiko [#1052](https://github.com/JetBrains/skiko/pull/1052), merged 2026-01-29,
which bumped Skiko's Skia pin to a `skia_use_egl=true` build) the only `GrGLMakeNativeInterface_*`
object in the published klib is the EGL one and there is no GLX object at all — so
`DirectContext.makeGL()`, which resolves through `GrGLMakeNativeInterface()`, **is** the EGL path.
Upstream is also cleaner than the fork: the fork's klib left `XOpenDisplay`/`glXSwapBuffers` and
friends undefined (they only linked because of `--allow-shlib-undefined`), while the upstream klib
needs nothing beyond `eglGetProcAddress`, the GLES2 entry points and fontconfig, all of which the
embedded `linkerOpts` already supply.

`makeEGL()` itself was never upstreamed and does not exist at any upstream version, so the one call
site (`apps/embedded/.../Renderer.kt`) now calls `makeGL()`.

**Consumed as:** `org.jetbrains.skiko:skiko-linuxarm64:0.9.47` from **Maven Central**, pinned by
`embedded.skikoVersion` in `gradle.properties`. Nothing needs to be cloned, built or published.

**Why 0.9.47 and not something newer.** 0.9.47 is the first EGL release, and its klib metadata is
identical to the fork's (`abi_version=1.8.0`, `compiler_version=2.0.10`, same `unique_name`), so
swapping it in changes exactly one variable. More importantly it is the last version that still
publishes `org.jetbrains.skiko.ClipboardManager` and `org.jetbrains.skiko.URIManager`: the Compose
Multiplatform Core fork's `PlatformClipboardManager.skiko.kt` and `PlatformUriHandler.skiko.kt` call
both, and they are **gone by 0.144.6**. Linking against 0.144.6 succeeds (Kotlin/Native partial
linkage downgrades the misses to `i:` messages) but leaves clipboard and `LocalUriHandler` as
runtime `IrLinkageError`s — an unacceptable trade for a chat app with a text field. Verified by
grepping `default/linkdata/` of the published klibs:

| `skiko-linuxarm64` | klib abi / compiler | `ClipboardManager` | `URIManager` |
|---|---|---|---|
| fork `0.9.37.3-SNAPSHOT` | 1.8.0 / 2.0.10 | present | present |
| **0.9.47** | **1.8.0 / 2.0.10** | **present** | **present** |
| 0.144.6 | 2.2.0 / 2.2.20 | removed | removed |

**The one unavoidable partial-linkage message.** `org.jetbrains.skia.ColorMatrix` became a
`value class` at 0.9.47, dropping the `vararg` constructor that the Compose core fork's
`SkiaColorFilter.skiko.kt:48` calls. Every EGL-capable Skiko has this change, so no version choice
avoids it. It is harmless here: the only reachable caller is `ColorFilter.colorMatrix`, which
nothing in this repo uses (the `ColorMatrix` hits under `data/mediautils` are `android.graphics`).
Upstream fixed it in Compose Multiplatform Core v1.11.0 by dropping the spread operator; if
`ColorFilter.colorMatrix` is ever needed on the embedded target, that one-liner has to be applied to
`forks/compose-multiplatform-core` and the forks republished.

Full evidence (per-version `llvm-nm` over the published klibs) is in
[`docs/reviews/2026-09-07-fork-drop-analysis.md`](reviews/2026-09-07-fork-drop-analysis.md) §Q1.

## 4. MeshCore

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

## 5. Meshtastic Firmware

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

## 6. gattlib

**What:** the BLE GATT client library used for the Central role on the embedded target. Unlike the other five
entries this is a git submodule, not a `forks/` checkout: `data/remote/transport/bluetooth/native/gattlib`,
pinned by SHA, so `.gitmodules` and the submodule pointer are the whole mechanism.

**Why:** gdbus-codegen cached-property getters return `NULL` once a peer's BlueZ objects have gone away
mid-discovery, and gattlib dereferenced four of them — `gattlib_string_to_uuid()` passes its argument to
`strlen()`, the flags loop dereferences the array head, and `gattlib_discover_char_range()` hands the Device
property to `strcmp()`. The library holds its own recursive mutex for the length of a discovery call, so this
cannot be guarded from Kotlin.

**Repo & Branch:** [fluxxion82/gattlib](https://github.com/fluxxion82/gattlib) `bitchat-null-guards`, branched
from upstream `labapart/gattlib` @ `1580056`.

**Changes:**
- `dbus/gattlib.c` (the compiled `BLUEZ_VERSION >= 5.38` branch only) — NULL guards on the four property
  getters; `g_clear_error()` in place of `g_error_free()` so a freed `GError` is not read again on the next
  iteration; `g_object_unref()` on the skip paths that leaked a proxy.

**Behaviour change:** discovery now returns fewer entries where it used to crash. A peer missing the bitchat
characteristic is already abandoned by `BlueZGattClientService.discoverCharacteristics()`, which is the
correct outcome — the scanner re-offers it under the existing backoff.

**Rebuild:**

```bash
docker run --platform linux/amd64 --rm \
  -v "$PWD/data/remote/transport/bluetooth/native:/build" \
  bitchat-linux-arm64-cross bash /build/build-gattlib-linux-arm64.sh
```

Measured at **23 s**, and byte-reproducible: two consecutive builds of the same source produce identical
archives. So unlike Arti and libsodium this one is safe to rebuild casually — the blanket "the prebuilt native
archives take hours to rebuild" warning in `CLAUDE.md` §4 is about those, not about gattlib. Note that
`build-gattlib-linux-arm64.sh:43` does `rm -rf` on the build directory, so copy the existing `libgattlib.a`
aside first if you want a guaranteed rollback.

**Restoring the patch if the submodule is reset.** The change lives in a commit on the fork, so
`git submodule update --init data/remote/transport/bluetooth/native/gattlib` restores it from `.gitmodules`.
If the submodule is ever pointed back at `labapart/gattlib`, recover with:

```bash
cd data/remote/transport/bluetooth/native/gattlib
git remote set-url origin https://github.com/fluxxion82/gattlib.git
git fetch origin bitchat-null-guards
git checkout f647d32657207143b8acc9aa5ab264a07661fcb7
```

No upstream PR has been opened against `labapart/gattlib` yet.

---

## First-Time Setup Checklist

Follow these steps in order on a new development machine to build the embedded target:

### 1. Clone forks

```bash
cd bitchat/forks
git clone -b linux-1.10.0 https://github.com/fluxxion82/compose-multiplatform-core.git
git clone -b release/1.10 https://github.com/fluxxion82/compose-multiplatform.git
git clone -b sa_linux_4.2.2 https://github.com/fluxxion82/koin.git
```

Skiko is not on this list any more — it comes from Maven Central (see
[Skiko: no longer forked](#skiko-no-longer-forked)).

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
cd forks/koin/projects   # the Gradle root is projects/, not the repo root
./gradlew publishToMavenLocal
```

### 5. Create sysroot (see [embedded README](../apps/embedded/README.md) Step 1)

### 6. Verify build

```bash
cd bitchatKmp
./gradlew -Pembedded.enabled=true :apps:embedded:linkDebugExecutableLinuxArm64
```

If this succeeds, all forked dependencies are correctly in place.

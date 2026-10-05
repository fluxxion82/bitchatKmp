# Embedded Linux ARM64 Compose Setup Notes

This document covers the setup required to run Compose Multiplatform on embedded Linux ARM64 (Orange Pi Zero 3), including resource loading, rendering architecture, and known issues.

> For a consolidated guide to all forked libraries (Compose, Koin, etc.), see [FORKED_LIBRARIES.md](../../docs/FORKED_LIBRARIES.md). Skiko is no longer forked — upstream `skiko-linuxarm64:0.150.1` is used. Its bundled Skia has been EGL-only since 0.9.47, so `DirectContext.makeGL()` is the EGL path.

## Overview

The embedded app renders Compose UI directly to a framebuffer using:
- **DRM** (Direct Rendering Manager) for display output
- **GBM** (Generic Buffer Management) for buffer allocation
- **EGL** for OpenGL ES context
- **Skia** for 2D rendering
- **Compose Multiplatform** for UI

## Compose Resources for linuxArm64

### The Problem

Compose Resources (`stringResource()`, images, etc.) failed at runtime with:
```
MissingResourceException: Missing resource with path: composeResources/.../strings.commonMain.cvr
```

**Root cause**: The `LinuxArm64ResourceReader` only looked for resources:
1. At absolute paths
2. Via `COMPOSE_RESOURCES_PATH` environment variable
3. Relative to current working directory

It **didn't look relative to the executable**, which is how deployed native apps work.

### The Solution (Two Parts)

#### Part 1: Runtime Fix - Update LinuxArm64ResourceReader

**File** (in the fork checkout beside this repository): `../forks/compose-multiplatform/components/resources/library/src/linuxArm64Main/kotlin/org/jetbrains/compose/resources/ResourceReader.linuxArm64.kt`

Added executable-relative path resolution using `/proc/self/exe`:

```kotlin
// Directory containing the executable, resolved via /proc/self/exe
private val executableDir: String by lazy {
    memScoped {
        val buffer = allocArray<ByteVar>(PATH_MAX)
        val len = readlink("/proc/self/exe", buffer, PATH_MAX.toULong())
        if (len > 0) {
            // readlink doesn't null-terminate, so extract only valid bytes
            val bytes = ByteArray(len.toInt()) { i -> buffer[i] }
            val exePath = bytes.decodeToString()
            exePath.substringBeforeLast('/')
        } else {
            ""
        }
    }
}

private fun resolveResourcePath(path: String): String {
    if (path.startsWith("/")) return path

    if (resourceBasePath.isNotEmpty()) {
        return "$resourceBasePath/$path"
    }

    // Try paths relative to executable
    if (executableDir.isNotEmpty()) {
        val candidates = listOf(
            "$executableDir/compose-resources/$path",
            "$executableDir/$path"
        )
        candidates.firstOrNull { fileExists(it) }?.let { return it }
    }

    return path
}
```

**Key detail**: `readlink()` doesn't null-terminate, so we must manually extract only the valid bytes.

#### Part 2: Build-time Fix - Gradle Task for Resource Syncing

**File** (in the fork checkout beside this repository): `../forks/compose-multiplatform/gradle-plugins/compose/src/main/kotlin/org/jetbrains/compose/resources/LinuxResources.kt`

Created a Gradle task (similar to iOS) that copies resources next to the executable:

```kotlin
internal fun Project.configureSyncLinuxComposeResources(
    kotlinExtension: KotlinMultiplatformExtension
) {
    kotlinExtension.targets.withType(KotlinNativeTarget::class.java).all { nativeTarget ->
        if (nativeTarget.konanTarget.family == Family.LINUX) {
            nativeTarget.binaries.withType(Executable::class.java).all { executable ->
                val syncTask = tasks.register<Copy>("syncComposeResourcesFor${executable.name}") {
                    from(executableResources)
                    into(executable.outputDirectory.resolve("compose-resources"))
                }
                executable.linkTaskProvider.dependsOn(syncTask)
            }
        }
    }
}
```

Wired into `ComposeResources.kt`:
```kotlin
configureSyncIosComposeResources(kotlinExtension)
configureSyncLinuxComposeResources(kotlinExtension)  // Added
```

### Deployment

Resources are NOT embedded in the executable (unlike an Android APK or iOS bundle), so the binary and `compose-resources/` travel together. `scripts/deploy-pi.sh` ships each build as `/opt/bitchat/releases/<sha12>[-dirty]-<build>-<digest8>/` containing `bitchat-embedded.kexe`, `compose-resources/` beside it (the reader above finds it through `/proc/self/exe`), `bitchat.service`, `wait-for-input-devices.sh`, `bluetooth-bitchat-ble.conf`, `BUILD_INFO` and `SHA256SUMS`. `/opt/bitchat/releases/current` is a symlink the script swaps atomically (`ln -sfn` to `current.tmp`, then `mv -T`), and `bitchat.service` runs `/opt/bitchat/releases/current/bitchat-embedded.kexe` with that directory as `WorkingDirectory`, so rolling back is re-pointing the symlink and restarting the unit. `COMPOSE_RESOURCES_PATH` remains an override for running a copy from somewhere else.

`bitchat-embedded.kexe --version` prints the build identity (`bitchat-embedded <version> (<sha12>, <branch>, clean|dirty, debug|release, built <time>)`) and exits before touching DRM, EGL or evdev, so it is safe to run on the device while the service holds the display. The same line is the second thing the service logs at startup. The deploy script takes the expected line from the `bitchat-embedded.build-info` sidecar that `:apps:embedded:link*ExecutableLinuxArm64` writes next to the kexe (which also carries the executable's SHA-256) and requires both the on-device `--version` output and the new invocation's journal to match it exactly. A tree counts as dirty when any file under the roots that feed the binary is modified or untracked (see the comment in `apps/embedded-common/build.gradle.kts`); dirty builds are stamped with the wall clock and get a `-dirty` release name.

### Rebuilding the Fork

After modifying the compose-multiplatform fork:

```bash
# Publish gradle plugin
cd ../forks/compose-multiplatform/gradle-plugins   # the fork checkout sits beside this repository
./gradlew publishToMavenLocal

# Publish resources library for linuxArm64
cd ../components
./gradlew :resources:library:compileKotlinLinuxArm64 --rerun-tasks
./gradlew :resources:library:publishLinuxArm64PublicationToMavenLocal

# Rebuild bitchatKmp with fresh dependencies
cd /path/to/bitchatKmp
./gradlew -Pembedded.enabled=true :apps:embedded:clean :apps:embedded:linkDebugExecutableLinuxArm64 \
    --no-build-cache --refresh-dependencies
```

---

## Compose Layout Race Conditions

### Problem 1: animateScrollToItem During Layout

```
IllegalArgumentException: performMeasureAndLayout called during measure layout
```

**Cause**: `LaunchedEffect` calling `listState.animateScrollToItem()` during the initial layout phase.

**Fix**: Add `yield()` before scroll operations to let layout complete:

```kotlin
LaunchedEffect(messages.size) {
    if (messages.isNotEmpty()) {
        yield()  // Let layout complete first
        listState.animateScrollToItem(0)
    }
}
```

### Problem 2: Render Loop vs Recomposition Race

A render loop that calls `scene.render()` while a coroutine on another thread is performing layout fails the same
way. An earlier version of the app wrapped the render in a try/catch and dropped the frame. That workaround is gone:
Compose work and rendering now run on one thread (next section), so the race cannot occur.

---

## Event-Driven Rendering

The app renders only when Compose has invalidated the scene, and does everything on the main thread. An earlier
version ran a continuous ~30 FPS loop with an empty `invalidate` callback; that design has been replaced.

- **Invalidation.** `CanvasLayersComposeScene` is created with `invalidate = { stateRef.value?.requestRender() }`
  (`Main.kt`). `State.requestRender()` sets an atomic flag and `State.needsRender` reads it (`State.kt`).
- **One thread.** The scene's coroutine context is a `FlushCoroutineDispatcher` (`FlushCoroutineDispatcher.kt`): it
  queues dispatched tasks and runs them only when the main loop calls `flush()`. Recomposition, layout and rendering
  therefore never overlap, which is also what prevents "Detected multithreaded access to SnapshotStateObserver".
- **The loop.** `Main.kt` blocks in `select()` on the DRM fd and the touch and keyboard evdev fds, with no timeout.
  On wake-up it feeds touch and keyboard events to the scene, flushes the dispatcher and, when the DRM fd is
  readable, calls `drmHandleEvent`, which runs `pageFlipHandler`.
- **Page flips drive frames.** `pageFlipHandler` (`PageFlip.kt`) flushes the dispatcher again, then either renders a
  frame or, when nothing was invalidated, re-queues a flip of the buffer already on screen. `renderFrame` releases the
  previous buffer, renders the scene to the Skia canvas, calls `eglSwapBuffers`, locks the new front buffer, gets its
  DRM framebuffer (`DrmFramebuffer.kt`) and queues `drmModePageFlip`. Either way the next page-flip event arrives on
  the next vblank, so the loop wakes once per refresh but renders only when the scene changed. `initialRender` shows
  the first frame with `drmModeSetCrtc` and queues the first flip to start the cycle.
- **Startup log.** The loop announces itself with `[Main] Entering event-driven loop (power-efficient mode)`.

One consequence: with no timeout on `select()`, queued Compose tasks run only when an input or page-flip event wakes
the loop. The re-queued flip is what keeps those wake-ups coming at the display's refresh rate while the UI is idle.

### Related implementations

- **Jake Wharton's mosaic**: Terminal UI with Compose - uses `CoroutineScope` and `launch` for rendering
- **Compose for Desktop**: `ComposeWindow` uses Swing's EDT and `revalidate()` pattern
- **Retired Skiko EGL fork**: this project used it before upstream `skiko-linuxarm64` shipped EGL-only Skia in 0.9.47 (kept here for history only)

---

## Required Changes Summary

### In compose-multiplatform fork:

1. `components/resources/library/src/linuxArm64Main/.../ResourceReader.linuxArm64.kt`
   - Added `/proc/self/exe` resolution
   - Added `compose-resources/` path fallback

2. `gradle-plugins/compose/src/main/kotlin/.../LinuxResources.kt` (new file)
   - Resource sync task for Linux native executables

3. `gradle-plugins/compose/src/main/kotlin/.../ComposeResources.kt`
   - Added `configureSyncLinuxComposeResources()` call

### In bitchatKmp:

1. `apps/embedded/build.gradle.kts`
   - Added `jetbrains-compose` plugin for resource syncing

2. `presentation/design/.../MessagesList.kt`
   - Added `yield()` before `animateScrollToItem()` calls

3. `apps/embedded/.../Main.kt`, `PageFlip.kt`, `State.kt`, `FlushCoroutineDispatcher.kt`
   - Event-driven render loop on a single thread (replaced the earlier try-catch around `scene.render()`)

# Embedded Linux ARM64 Compose Setup Notes

This document covers the setup required to run Compose Multiplatform on embedded Linux ARM64 (Raspberry Pi), including resource loading, rendering architecture, and known issues.

> For a consolidated guide to all forked libraries (Compose, Koin, Skiko, etc.), see [FORKED_LIBRARIES.md](../../docs/FORKED_LIBRARIES.md).

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

**File**: `forks/compose-multiplatform/components/resources/library/src/linuxArm64Main/kotlin/org/jetbrains/compose/resources/ResourceReader.linuxArm64.kt`

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

**File**: `forks/compose-multiplatform/gradle-plugins/compose/src/main/kotlin/org/jetbrains/compose/resources/LinuxResources.kt`

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

Resources are NOT embedded in the executable (unlike an Android APK or iOS bundle), so the binary and `compose-resources/` travel together. `scripts/deploy-pi.sh` ships each build as `/opt/bitchat/releases/<sha12>[-dirty]-<build>-<digest8>/` containing `bitchat-embedded.kexe`, `compose-resources/` beside it (the reader above finds it through `/proc/self/exe`), `bitchat.service`, `BUILD_INFO` and `SHA256SUMS`. `/opt/bitchat/releases/current` is a symlink the script swaps atomically (`ln -sfn` to `current.tmp`, then `mv -T`), and `bitchat.service` runs `/opt/bitchat/releases/current/bitchat-embedded.kexe` with that directory as `WorkingDirectory`, so rolling back is re-pointing the symlink and restarting the unit. `COMPOSE_RESOURCES_PATH` remains an override for running a copy from somewhere else.

`bitchat-embedded.kexe --version` prints the build identity (`bitchat-embedded <version> (<sha12>, <branch>, clean|dirty, debug|release, built <time>)`) and exits before touching DRM, EGL or evdev, so it is safe to run on the device while the service holds the display. The same line is the second thing the service logs at startup. The deploy script takes the expected line from the `bitchat-embedded.build-info` sidecar that `:apps:embedded:link*ExecutableLinuxArm64` writes next to the kexe (which also carries the executable's SHA-256) and requires both the on-device `--version` output and the new invocation's journal to match it exactly. A tree counts as dirty when any file under the roots that feed the binary is modified or untracked (see the comment in `apps/embedded/build.gradle.kts`); dirty builds are stamped with the wall clock and get a `-dirty` release name.

### Rebuilding the Fork

After modifying the compose-multiplatform fork:

```bash
# Publish gradle plugin
cd forks/compose-multiplatform/gradle-plugins
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

The continuous render loop can call `scene.render()` while a coroutine is performing layout.

**Current workaround**: Try-catch around render:

```kotlin
renderer.renderFrame { skiaCanvas ->
    skiaCanvas.clear(Color.BLACK)
    try {
        scene.render(skiaCanvas.asComposeCanvas(), frameStart)
    } catch (e: IllegalArgumentException) {
        // Race condition: layout in progress, skip this frame
    }
}
```

---

## Invalidation-Driven Rendering (Proper Fix)

The current render loop runs continuously at ~30 FPS regardless of whether the UI changed. A proper implementation would only render when Compose signals invalidation.

### Current Architecture (Continuous Loop)

```kotlin
val scene = CanvasLayersComposeScene(
    // ...
    invalidate = {},  // Empty - we ignore invalidation signals
)

while (true) {
    scene.render(canvas, timestamp)  // Render every frame
    sleep(33ms)  // ~30 FPS
}
```

**Problems**:
- Wastes CPU/GPU when UI is static
- Race conditions between render loop and Compose coroutines
- No synchronization with Compose's frame clock

### Proper Architecture (Invalidation-Driven)

```kotlin
// Thread-safe flag for pending invalidation
val needsRender = AtomicBoolean(true)

val scene = CanvasLayersComposeScene(
    // ...
    invalidate = { needsRender.set(true) },  // Signal when UI needs redraw
)

while (true) {
    // Only render if invalidated
    if (needsRender.getAndSet(false)) {
        // Use Compose's frame clock for proper timing
        val frameTime = withFrameNanos { it }

        renderer.renderFrame { skiaCanvas ->
            skiaCanvas.clear(Color.BLACK)
            scene.render(skiaCanvas.asComposeCanvas(), frameTime)
        }

        egl.swapBuffers()
        presentToDrm(...)
    } else {
        // Sleep or wait for vsync when idle
        waitForVsyncOrTimeout(16ms)
    }
}
```

### Full Implementation Would Require

1. **Proper invalidation tracking**:
   ```kotlin
   private val pendingInvalidation = AtomicBoolean(false)

   val scene = CanvasLayersComposeScene(
       invalidate = {
           pendingInvalidation.set(true)
           wakeRenderThread()  // Unblock if sleeping
       },
   )
   ```

2. **Frame clock integration**:
   ```kotlin
   // Use Compose's BroadcastFrameClock or MonotonicFrameClock
   val frameClock = BroadcastFrameClock()

   // In render loop:
   frameClock.sendFrame(System.nanoTime())
   ```

3. **VSync synchronization** (optional but ideal):
   ```kotlin
   // Wait for display vsync before rendering
   drmWaitVBlank(drm.fd, ...)
   ```

4. **Proper coroutine context**:
   ```kotlin
   // Run Compose on a dedicated single-threaded dispatcher
   val composeDispatcher = newSingleThreadContext("ComposeUI")

   val scene = CanvasLayersComposeScene(
       coroutineContext = composeDispatcher,
       // ...
   )

   // Render from same thread to avoid races
   withContext(composeDispatcher) {
       scene.render(...)
   }
   ```

5. **hasInvalidations() check** (if available):
   ```kotlin
   // Some Compose scene implementations expose this
   if (scene.hasInvalidations()) {
       scene.render(...)
   }
   ```

### Reference Implementations

- **Jake Wharton's mosaic**: Terminal UI with Compose - uses `CoroutineScope` and `launch` for rendering
- **Compose for Desktop**: `ComposeWindow` uses Swing's EDT and `revalidate()` pattern
- **JakeWharton/skiko** (`jw-egl-0.9.37.3-port`): EGL-enabled Skiko fork used by this project

### Why We Use Continuous Loop (For Now)

1. **Simpler to implement** - no threading complexity
2. **Touch input requires polling** - evdev needs regular reads anyway
3. **Embedded displays often want constant refresh** - prevents screen artifacts
4. **The race condition workaround is acceptable** - just skips occasional frames

The proper fix would be valuable for:
- Battery-powered devices (save power when idle)
- Complex UIs with heavy composition (only render when needed)
- Smoother animations (proper frame clock integration)

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

3. `apps/embedded/.../Main.kt`
   - Added try-catch around `scene.render()` for race condition handling

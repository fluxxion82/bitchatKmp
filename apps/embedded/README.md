# Bitchat Embedded

Kotlin/Native `linuxArm64` binary for running bitchat on an Orange Pi Zero 3 with an Elecrow 5" HDMI touch display (800x480) and M5Stack CardKB I2C keyboard. No JVM, no desktop environment.

Follows [Jake Wharton's composeui-lightswitch](https://github.com/nickallendev/composeui-lightswitch) pattern: DRM/GBM/EGL rendering pipeline.

## Prerequisites

- **macOS** (Apple Silicon or Intel) — the Kotlin/Native cross-compiler runs on macOS and targets linuxArm64
- **Docker** — used to extract Linux ARM64 headers and libraries from Debian packages

## Building

### 0. Set up Jake Wharton's EGL-enabled Compose/Skiko (one-time setup)

> For the full picture of all forked libraries and first-time setup, see [FORKED_LIBRARIES.md](../../docs/FORKED_LIBRARIES.md).

Standard Skiko uses GLX (X11) for OpenGL function loading, which doesn't work on headless Linux without X11. We use Jake Wharton's custom builds that use EGL instead.

Clone Jake's repositories and copy his pre-built maven artifacts:

```bash
# Clone Jake's repos (from the bitchat root, not bitchatKmp)
cd ..
mkdir -p jake && cd jake
git clone https://github.com/JakeWharton/composeui-lightswitch
cd composeui-lightswitch

# Copy the maven folder to our embedded app
cp -r maven ../../bitchatKmp/apps/embedded/
```

The `maven/` directory contains:
- `org.jetbrains.skiko:skiko-linuxarm64:0.9.37.3-SNAPSHOT` — EGL-enabled Skiko (uses `eglGetProcAddress` instead of `glXGetProcAddress`)
- `org.jetbrains.compose.*:*-linuxarm64:9999.0.0-SNAPSHOT` — Compose UI artifacts compatible with the EGL Skiko

The `maven/` directory is gitignored and must be set up on each machine.

### 1. Create the sysroot (one-time setup)

The Kotlin/Native compiler runs on macOS but needs Linux ARM64 headers (for cinterop) and shared libraries (for linking). These are extracted from Debian bookworm packages into a local `sysroot/` directory.

From the `bitchatKmp/` directory:

```bash
docker run --rm --platform linux/arm64 \
  -v "$(pwd)/apps/embedded/sysroot":/out \
  arm64v8/debian:bookworm bash -c '
    apt-get update -qq && \
    cd /tmp && \
    apt-get download \
      libdrm-dev libgbm-dev libegl-dev libgles-dev \
      linux-libc-dev libc6-dev \
      libdrm2 libgbm1 libegl1 libegl-mesa0 \
      libgles2 libglvnd0 libglapi-mesa \
      libfontconfig1 libfontconfig-dev \
      libfreetype6 libfreetype-dev \
      libpng16-16 libpng-dev \
      zlib1g zlib1g-dev \
      libbz2-1.0 libbz2-dev \
      libexpat1 libexpat1-dev \
      libgl1 libgl-dev libglx0 libglx-dev libglx-mesa0 \
      libx11-6 libx11-dev libxext6 libxcb1 libxcb1-dev \
      2>/dev/null && \
    for f in *.deb; do dpkg-deb -x "$f" /out; done && \
    echo "Sysroot created successfully"
  '
```

This creates `apps/embedded/sysroot/` containing:
- `usr/include/` — C headers for DRM, GBM, EGL, GLES2, evdev, I2C
- `usr/lib/aarch64-linux-gnu/` — ARM64 shared libraries for linking

The `sysroot/` directory is gitignored and must be recreated on each machine.

### 2. Build the binary

```bash
# Debug build
./gradlew :apps:embedded:linkDebugExecutableLinuxArm64

# Release build
./gradlew :apps:embedded:linkReleaseExecutableLinuxArm64
```

Output:
```
apps/embedded/build/bin/linuxArm64/debugExecutable/bitchat-embedded.kexe
apps/embedded/build/bin/linuxArm64/releaseExecutable/bitchat-embedded.kexe
```

## Deploying to the Device

### Device setup (one-time)

1. Flash **Armbian Minimal / IOT** for Orange Pi Zero 3 onto an SD card
2. Connect the Elecrow 5" display via micro-HDMI adapter
3. Boot and install runtime dependencies:

```bash
sudo apt update
sudo apt install \
  libdrm2 libgbm1 libegl-mesa0 libgles2-mesa \
  libfontconfig1 libfreetype6 \
  libpng16-16 zlib1g libbz2-1.0 libexpat1 \
  libgl1 libglx-mesa0 libx11-6 libxcb1
```

4. Verify DRM is available:

```bash
ls /dev/dri/card*
```

### Deploy and run

```bash
scp apps/embedded/build/bin/linuxArm64/debugExecutable/bitchat-embedded.kexe user@orangepi:/tmp/
ssh user@orangepi '/tmp/bitchat-embedded.kexe'
```

The binary opens `/dev/dri/card0`, finds the display mode (targeting 800x480), initializes EGL with OpenGL ES 2.0, and renders a solid teal frame to verify the pipeline works.

## Architecture

The display pipeline follows the DRM/KMS pattern with Compose Multiplatform:

```
Compose UI (@Composable functions)
    |
    v
MultiLayerComposeScene -- Compose scene management
    |
    v
Skia DirectContext (EGL backend) -- 2D graphics rendering
    |
    v
OpenGL ES 3.1 (Mali-G31 Panfrost) -- GPU rendering
    |
    v
EGL surface -- OpenGL ES context on GBM
    |
    v
GBM (buffer management) -- double-buffered surface
    |
    v
DRM (/dev/dri/card0) -- kernel mode setting, scanout
    |
    v
HDMI Display (800x480 @ 60Hz)
```

### Source files

```
src/linuxArm64Main/kotlin/com/bitchat/embedded/
    Main.kt      -- entry point, Compose scene, render loop
    Drm.kt       -- DRM device, connector, CRTC, mode selection
    Gbm.kt       -- GBM device + surface for buffer management
    Egl.kt       -- EGL display, context, surface initialization
    Renderer.kt  -- Skia DirectContext and per-frame rendering
```

### cinterop definitions

```
src/nativeInterop/cinterop/
    drm.def    -- xf86drm.h, xf86drmMode.h
    gbm.def    -- gbm.h
    egl.def    -- EGL/egl.h, EGL/eglext.h
    gles2.def  -- GLES2/gl2.h
    evdev.def  -- linux/input.h (touch input, future use)
    i2c.def    -- linux/i2c-dev.h (CardKB keyboard, future use)
```

## Troubleshooting

**`Could not find kotlin-native-prebuilt-...-linux-aarch64.tar.gz`**
You're trying to build inside an ARM64 Docker container. The Kotlin/Native compiler only runs on macOS and Linux x86_64. Build on your Mac directly — the sysroot provides the ARM64 headers/libraries the compiler needs.

**`unable to find library -ldrm`**
The sysroot hasn't been created yet. Run the Docker command from step 1 above.

**`undefined reference: stat64@GLIBC_2.33`**
The `--allow-shlib-undefined` linker flag is missing. This is already set in `build.gradle.kts` — the glibc symbols resolve at runtime on the device.

**Blank screen on device**
- Verify DRM device exists: `ls /dev/dri/card*`
- Check if another process holds the display: `sudo fuser /dev/dri/card0`
- Try running as root: `sudo /tmp/bitchat-embedded.kexe`
- Verify GPU libraries: `apt install mesa-utils && eglinfo`

# Bitchat Embedded

Kotlin/Native `linuxArm64` binary for running bitchat on an Orange Pi Zero 3 with an Elecrow 5" HDMI touch display (800x480) and M5Stack CardKB I2C keyboard. No JVM, no desktop environment.

Uses a DRM/GBM/EGL rendering pipeline with upstream Skiko (`skiko-linuxarm64`, whose bundled Skia is EGL-only since 0.9.47).

## Prerequisites

- **macOS** (Apple Silicon or Intel) — the Kotlin/Native cross-compiler runs on macOS and targets linuxArm64
- **Docker** — used to extract Linux ARM64 headers and libraries from Debian packages

## Building

### 0. Publish the forked Compose and Koin artifacts (one-time setup)

> For the full picture of all forked libraries and first-time setup, see [FORKED_LIBRARIES.md](../../docs/FORKED_LIBRARIES.md).

Compose Multiplatform and Koin have no upstream `linuxArm64` artifacts, so local forks must be built
and published to `~/.m2` before this module will resolve. Skiko needs nothing: it is resolved from
Maven Central as `org.jetbrains.skiko:skiko-linuxarm64` (pinned by `embedded.skikoVersion` in
`gradle.properties`), and since 0.9.47 its bundled Skia is built with `skia_use_egl=true`, so
`DirectContext.makeGL()` loads GL through `eglGetProcAddress` — no X11/GLX, and no Skiko fork.

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
./gradlew -Pembedded.enabled=true :apps:embedded:linkDebugExecutableLinuxArm64

# Release build
./gradlew -Pembedded.enabled=true :apps:embedded:linkReleaseExecutableLinuxArm64
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

`scripts/deploy-pi.sh` links the binary, ships it to the Pi as a versioned release, verifies it there and (re)starts it as `bitchat.service`:

```bash
scripts/deploy-pi.sh                    # debug build to $PI_HOST (default sterling@192.168.4.58)
scripts/deploy-pi.sh --release          # release build
scripts/deploy-pi.sh --no-build         # reuse the existing link output (sidecar must match the kexe)
scripts/deploy-pi.sh --no-restart       # upload, verify and switch current; do not install/restart the unit
scripts/deploy-pi.sh --dry-run          # build and stage locally, print BUILD_INFO and SHA256SUMS, no ssh
scripts/deploy-pi.sh --host user@host   # or export PI_HOST=user@host
```

In order: runs `./gradlew -Pembedded.enabled=true :apps:embedded:link{Debug,Release}ExecutableLinuxArm64`; reads the `bitchat-embedded.build-info` sidecar the link task writes next to the kexe and checks the executable's SHA-256 against it; stages the kexe, `compose-resources/`, `systemd/bitchat.service`, `systemd/wait-for-input-devices.sh`, `BUILD_INFO` and a `SHA256SUMS` manifest; rsyncs them to `/opt/bitchat/releases/<sha12>[-dirty]-<build>-<digest8>/`; runs `sha256sum -c` and `bitchat-embedded.kexe --version` on the device and requires the output to equal the sidecar's `identity=` line; swaps the `/opt/bitchat/releases/current` symlink atomically; installs the unit through the sudoers rule and reloads systemd; re-reads `After=` of both `bitchat.service` and `multi-user.target` and warns loudly (never fatally) if the boot ordering cycle is back (see "Boot ordering" below); enables and restarts it; then polls the new invocation's journal until it logs that same identity line, and keeps polling for about four more seconds (two 2 s polls) to confirm the unit is still `active` under the same invocation before declaring success. On a clean tree a second run is UP-TO-DATE in Gradle, reuses the same release directory and rewrites only `BUILD_INFO` and `SHA256SUMS`.

Release layout on the device:

```
/opt/bitchat/releases/
├── 73fdbbf4f5ce-debug-5222940b/      # <sha12>[-dirty]-<debug|release>-<digest8 of the payload>
│   ├── bitchat-embedded.kexe
│   ├── compose-resources/            # must sit beside the binary (resolved via /proc/self/exe)
│   ├── bitchat.service
│   ├── wait-for-input-devices.sh     # ExecStartPre=; waits for CardKB and touch, always exits 0
│   ├── BUILD_INFO                    # sidecar fields + release, deployed_from, deployed_at
│   └── SHA256SUMS
└── current -> /opt/bitchat/releases/73fdbbf4f5ce-debug-5222940b
```

`bitchat-embedded.kexe --version` prints the identity line and exits without opening DRM, e.g.
`bitchat-embedded 1.0.0 (73fdbbf4f5ce, reentry/session-2, clean, debug, built 2026-09-06T21:16:43-07:00)`.
The service logs the same line as its second journal line at startup.

Every deploy also (re)points `~/bitchat-embedded.kexe` at
`/opt/bitchat/releases/current/bitchat-embedded.kexe`, so the binary in the home directory is always the current
release. It is a symlink, never a copy: `/proc/self/exe` resolves symlinks fully, so the app still finds the release
directory's `compose-resources/` beside its real path. If a regular file is already sitting there (a hand-copied binary
from before build identity, with no `compose-resources/` next to it, which fails at startup with
`MissingResourceException`), the deploy renames it once to `~/bitchat-embedded.kexe.stale-<date>` and says so; delete it
by hand whenever you like. On the device:

```bash
~/bitchat-embedded.kexe --version          # safe any time: --version exits before touching DRM
sudo systemctl stop bitchat.service        # the service holds DRM master; stop it before running the app by hand
~/bitchat-embedded.kexe
sudo systemctl start bitchat.service       # hand it back afterwards
```

One-time device preparation (needs the password once):

```bash
sudo mkdir -p /opt/bitchat/releases && sudo chown sterling:sterling /opt/bitchat/releases
sudo install -o sterling -g sterling -m 644 /dev/null /opt/bitchat/bitchat.service   # placeholder; the script overwrites it in place
sudo usermod -aG systemd-journal sterling                                             # journal read access for the post-restart check
```

plus a sudoers entry that lets the ssh user run, without a password, `systemctl daemon-reload`,
`systemctl {start,stop,restart,status,enable,disable,is-active} bitchat.service` and
`install -m 644 -o root -g root /opt/bitchat/bitchat.service /etc/systemd/system/bitchat.service`.
Key-based ssh must work (`-o BatchMode=yes`) and the user must be in the `video`, `render` and `input` groups.
The post-restart check runs `journalctl` and `systemctl is-active`/`show` without sudo, so the ssh user also needs
journal read access (the `systemd-journal` group above, or an equivalent); reconnect after `usermod` so the new group applies.

Boot ordering: `bitchat.service` is `After=multi-user.target cardkb.service xpt2046-touch.service`, and naming the
target there is load-bearing. `cardkb.service` is the CardKB I2C-to-uinput daemon and is tracked in this repo at
`scripts/cardkb/cardkb.service` (installed by `scripts/cardkb/install-cardkb.sh`); `xpt2046-touch.service` is the touch
daemon and exists only on the device, not in this repo. Both are `After=multi-user.target` and `WantedBy=multi-user.target`.

The measured rule: systemd adds an implicit `After=` from a target to every unit that target `Wants` — *unless* an
ordering dependency between the target and the unit already exists, which suppresses the implicit edge. On the device:

```
multi-user.target After bitchat.service        -> YES   (while bitchat.service declared no ordering with the target)
multi-user.target After cardkb.service         -> no    (suppressed: cardkb.service is After=multi-user.target)
multi-user.target After xpt2046-touch.service  -> no    (suppressed the same way)
```

So dropping `multi-user.target` from `After=` while keeping `After=cardkb.service` left the implicit
multi-user.target -> bitchat.service edge in place and closed the cycle bitchat -> cardkb -> multi-user.target ->
bitchat. systemd breaks a cycle by deleting a job, and at the next boot it deleted `bitchat.service/start`
(`multi-user.target: Found ordering cycle ... Job bitchat.service/start deleted`), so the app never came up.
`systemctl restart` does not exercise this, which is why the deploy's post-restart check passed. Keeping the target in
`After=` is the fix; `deploy-pi.sh` re-runs the same two `systemctl show -p After` queries after installing the unit and
prints a loud ERROR banner if the cycle is back.

The app also scans for its input devices only once at startup (`KeyboardInput.findKeyboardDevice`,
`TouchInput.findTouchDevice`), so `ExecStartPre` runs `wait-for-input-devices.sh` (shipped in the release directory,
`TimeoutStartSec=120` covers it). It waits up to 20 s for both `CardKb-I2C` and `XPT2046 Touchscreen` to appear in
`/sys/class/input/event*/device/name` (the name is on the parent input device, not the event device) *and* for their `/dev/input/event*` node to be readable — udev applies permissions
asynchronously, so a sysfs name alone does not mean the app can open it — then always exits 0, logging what was missing
if it gave up. Run it by hand with an optional timeout:

```bash
/opt/bitchat/releases/current/wait-for-input-devices.sh 5
```

Autostart is only proven by a reboot; after one, check on the device:

```bash
journalctl -b -g 'ordering cycle'      # must print nothing
systemctl is-active bitchat.service    # active
journalctl -b -u bitchat.service       # ExecStartPre notice (if any), identity line, [Main] Entering event-driven loop
```

Logs, state and rollback (on the device):

```bash
journalctl -u bitchat.service -f                                   # follow the app log
systemctl is-enabled bitchat.service; systemctl is-active bitchat.service   # no sudo needed
ls /opt/bitchat/releases                                           # pick an older release, then:
ln -sfn /opt/bitchat/releases/<old> /opt/bitchat/releases/current.tmp \
  && mv -T /opt/bitchat/releases/current.tmp /opt/bitchat/releases/current \
  && sudo systemctl restart bitchat.service
```

A successful deploy prints the rollback command only when it replaced a different previous release; the first deploy and a same-release redeploy have nothing to roll back to. Old release directories are never deleted; remove them by hand.

At startup the binary prints its identity, opens `/dev/dri/card0`, picks the connected display mode, brings up GBM/EGL and Skia, opens the touch and CardKB evdev devices and enters the Compose render loop; `[Main] Entering event-driven loop` in the journal means it got that far.

Keyboard logging: `BITCHAT_INPUT_DEBUG` is read once at startup and only after `BuildIdentity.isDebug`, so release builds log no keyboard events at all whatever it is set to. Debug builds (what `deploy-pi.sh` ships by default) have three states:

| `BITCHAT_INPUT_DEBUG` | per key-down | |
|---|---|---|
| unset (default) or any other value | nothing after the first key | one `[Keyboard] first key event received (class=..., consumed=...); per-key logging off` line on the very first key-down, then silence |
| `classes` | `[Keyboard] class=<modifier\|control\|printable> consumed=<true\|false>` | which kind of key, never which key |
| `keys` | `[Keyboard] evdev=... key=... codePoint=... mods=... consumed=...` | the typed text itself |

The journal is persistent, so per-key logging is opt-in: even the class-only trace leaks message and password length, the capitalisation pattern and inter-keystroke timing, and `=keys` keylogs everything typed. Never set either while typing messages, passwords or other secrets, and unset it afterwards. The startup banner names the active mode. `KeyboardEventData.toString()` is redacted (no key code, no code point) so a stray `println("$event")` cannot quietly reintroduce a keylogger.

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
- Read the service log: `journalctl -u bitchat.service -n 50`; look for `[DRM]`, `[EGL]`, `[Touch]`, `[Keyboard]` lines before `[Main] Entering event-driven loop`
- Run it by hand while the service is stopped: `sudo systemctl stop bitchat.service && /opt/bitchat/releases/current/bitchat-embedded.kexe`
- Verify GPU libraries: `apt install mesa-utils && eglinfo`

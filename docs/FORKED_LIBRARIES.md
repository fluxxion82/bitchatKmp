# Forked Libraries

bitchatKmp targets `linuxArm64` (Orange Pi Zero 3), which Compose Multiplatform and parts of Koin do not support
upstream. Those libraries are forked and patched to produce `-linuxarm64` artifacts; the terminal UI uses a Mosaic fork
for Linux-console behaviour; and three native components (MeshCore, Meshtastic, gattlib) carry device patches. The
Kotlin forks live in `bitchat/forks/` (worktrees under `bitchat/forks/.worktrees/`), except Mosaic, which lives beside
the repo at `workspace/multiplatform/mosaic-wasm`.

Last refresh: 2026-10-02. Every Kotlin fork was rebased onto its newest upstream release and built with Kotlin
`2.5.0-Beta1`. On every fork branch the Kotlin bump is the **last** commit, so moving to the next Kotlin release is a
one-commit change per fork (see [Next Kotlin bump](#next-kotlin-bump)).

## Quick reference

| # | Library | Fork repo | Branch | Upstream base | Version published | Targets published |
|---|---|---|---|---|---|---|
| 1 | Compose Multiplatform Core | [fluxxion82/compose-multiplatform-core](https://github.com/fluxxion82/compose-multiplatform-core) | `linux-1.12.1` | `v1.12.1` (`a1a7f353336`) | per library, `<upstream>-embedded-SNAPSHOT` (below) | metadata, desktop (JVM), linuxArm64 |
| 2 | Compose Multiplatform | [fluxxion82/compose-multiplatform](https://github.com/fluxxion82/compose-multiplatform) | `linux-1.12.1` | `v1.12.1` (`bdd8e879b2`) | `1.12.1-embedded-SNAPSHOT` | Gradle plugin; `components-resources` metadata, desktop, linuxArm64 |
| 3 | Koin | [fluxxion82/koin](https://github.com/fluxxion82/koin) | `sa_linux_4.2.2-kotlin-2.5` | tag `4.2.2` (`dc86ef8d`) | `4.2.2-embedded-SNAPSHOT` | metadata, JVM, linuxArm64 (four modules) |
| 4 | Mosaic | [fluxxion82/mosaic](https://github.com/fluxxion82/mosaic) | `embedded` | `trunk` (`abc611c9`) | `0.19.0-embedded-SNAPSHOT` | all Mosaic publications |
| 5 | MeshCore | [fluxxion82/MeshCore](https://github.com/fluxxion82/MeshCore) | `orangepi-zero3-sx1276` | `ggodlewski/MeshCore` `linux` | native binary | built on the device |
| 6 | Meshtastic firmware | [fluxxion82/firmware](https://github.com/fluxxion82/firmware) | `orangepi-rfm95w` | `meshtastic/firmware` 2.7.x | native binary | built on the device |
| 7 | gattlib | [fluxxion82/gattlib](https://github.com/fluxxion82/gattlib) | `bitchat-null-guards` (`8482263`) | `labapart/gattlib` @ `1580056` | static library (submodule) | `scripts/build-native-linux-arm64.sh` |

Skiko is **not** forked: `org.jetbrains.skiko:skiko-linuxarm64:0.150.1` comes from Maven Central (see [Skiko](#skiko)).

Fork commits at the 2026-10-02 refresh (all pushed only when the owner decides):

| Fork | Commits on top of the upstream base |
|---|---|
| Core | `460352e37a2` Compose UI for Linux (Thomas Vos) · `4c3d3ecf4a0` Workaround for missing Dispatchers.Main (Thomas Vos) · `d1ac5634bf3` Port the Linux target to Compose 1.12 · `2d765d8dbac` Publish as `<upstream>-embedded-SNAPSHOT` · `5e437f50092` Update Kotlin to 2.5.0-Beta1 |
| Compose Multiplatform | `fdb56b46da` Add linuxArm64 to compose-resources · `fbefc7db92` Limit compose-resources to embedded targets on request · `e8003428fa` Update the components Gradle wrapper to 8.14.3 · `d246a3b8a5` Update Kotlin to 2.5.0-Beta1 |
| Koin | `b8559a3f` Add linuxArm64 support · `e491a64b` Update Kotlin to 2.5.0-Beta1 |
| Mosaic | 15 commits ending `31ec507b` Parse CSI Z as Shift+Tab · `2e742093` Test binarySearch on an empty array · `3e96796e` Update Burst to 2.14.0 · `3c532d14` Fix WAsm animation actuals · `bb346538` Update Kotlin to 2.5.0-Beta1 |

## Version names

Fork artifacts are named after the upstream release they are built from, with an `-embedded-SNAPSHOT` suffix, so a
coordinate says what it is (`ui-linuxarm64:1.12.1-embedded-SNAPSHOT` is Compose UI 1.12.1 plus the Linux port). Before
2026-10 the Compose fork published everything as `9999.0.0-SNAPSHOT` and Koin as plain `4.2.2`; those old artifacts
are still in `~/.m2` and are what bitchatKmp `main` used before the refresh.

| Core fork library key | Version |
|---|---|
| `COMPOSE` (runtime, ui, foundation, animation, material) | `1.12.1-embedded-SNAPSHOT` |
| `COMPOSE_MATERIAL3` | `1.12.1-embedded-SNAPSHOT` |
| `LIFECYCLE` | `2.11.0-embedded-SNAPSHOT` |
| `SAVEDSTATE` | `1.5.0-alpha01-embedded-SNAPSHOT` |
| `NAVIGATION` | `2.10.0-alpha05-embedded-SNAPSHOT` |
| `NAVIGATION_EVENT` | `1.1.1-embedded-SNAPSHOT` |
| `NAVIGATION_3`, `COMPOSE_MATERIAL3_ADAPTIVE` | `1.1.7-…`, `1.3.0-beta02-…` (not published; not used) |

Material3 is versioned after the Compose release rather than after the `1.9.0` default that the 1.12.1 Gradle plugin
bakes into its `compose.material3` accessor: the fork builds material3 from the `v1.12.1` tree.

**Consequence:** `1.12.1-embedded-SNAPSHOT` sorts *below* upstream `1.12.1`, so a fork version never wins a version
conflict on its own (the old `9999.0.0-SNAPSHOT` did). Every consumer must force the fork versions explicitly; see
[Build configuration](#build-configuration).

## Build configuration

Everything below is active only with the embedded profile (`-Pembedded.enabled=true`); a flagless build resolves
Compose 1.12.1 and Koin 4.2.2 from Maven Central and never touches `~/.m2`.

1. **Repositories** (`settings.gradle.kts`): `mavenLocal()` first. `embedded.composeForkVersion` also selects the
   Compose Gradle plugin version (`1.12.1-embedded-SNAPSHOT`) in `pluginManagement`.
2. **Versions** (`gradle.properties`): `embedded.composeForkVersion`, `embedded.material3ForkVersion`,
   `embedded.lifecycleForkVersion`, `embedded.savedstateForkVersion`, `embedded.navigationForkVersion`,
   `embedded.navigationEventForkVersion`, `embedded.koinForkVersion`, `embedded.skikoVersion`.
3. **Forcing** (root `build.gradle.kts`, the only place that maps groups to fork versions): an allow-list of the
   modules the forks actually publish, per group, each forced to its property. Modules the forks do not publish —
   Apple-only `*-uikit` modules, upstream `annotation-internal`/`collection-internal` forwards, Koin modules other than
   the four forked ones, `org.jetbrains.compose.desktop` — resolve from Maven Central as usual.
4. **Explicit platform artifacts** (`apps/embedded/build.gradle.kts`, `presentation/*/build.gradle.kts`): Kotlin/Native
   cannot resolve multiplatform metadata modules for an unsupported target, so the embedded modules declare
   `-linuxarm64` coordinates directly, using the same properties.

The core fork publishes only metadata, desktop and linuxArm64, but its root `.module` files still advertise the other
platforms' variants (Android, iOS, macOS, JS, Wasm, linuxX64). Nothing in the embedded build resolves those; a consumer
that does (Koin's common-metadata transform, for example) must let the Apple-only modules resolve upstream.
Restricting the fork's configured targets with `androidx.enabled.kmp.target.platforms=-mac` is not an option: the
fork's own build scripts then fail (`KotlinTargetWithTests with name 'iosSimulatorArm64' not found`).

## 1. Compose Multiplatform Core

**What:** Compose runtime, UI, foundation, animation, material, material3, plus the JetBrains lifecycle, savedstate,
navigation and navigation-event artifacts.

**Why:** upstream Compose UI has no Linux/Native target. The fork is based on Thomas Vos's Linux Compose work (his two
commits are carried with authorship) and ported forward to 1.12.

**What the port contains** (`d1ac5634bf3`): the Linux targets on 25 modules; about forty Linux actuals (text input,
key mapping, pointer and velocity tracking, focus, scrolling, selection, drag and drop, interop, URI handler, locale and
string casing, date formatting for the material3 pickers); a process-local clipboard (paste, copy and cut work from the
keyboard; there is no system clipboard on the device); a Linux owner-thread check for the UI dispatcher; the Apple
dispatcher actual moved to `appleMain`; project substitutions so Linux resolves inside the fork; and a workaround in
material3's `TextFieldDefaults.kt` for a Kotlin/Native 2.5 linkage bug with composable `fun interface` SAM
conversions across klibs (state-based `TextField` failed with `IrLinkageError ... TextFieldDecorator.Decoration`).

**Publishing** (`2d765d8dbac`): per-library versions in `gradle.properties`
(`jetbrains.publication.version.<KEY>`); `Version.kt` treats any `-SNAPSHOT` suffix as a snapshot so
`jbVerifyDependencyVersions` accepts the names; `publish-embedded.sh` publishes the closure bitchatKmp needs (29
projects, metadata + desktop + linuxArm64).

**Kotlin** (`5e437f50092`): Kotlin and the Compose compiler plugin at `2.5.0-Beta1`; buildSrc language level raised to
2.3 and library floors below 2.3 raised (2.5 rejects them); the Kotlin ABI tooling API change; `watchosArm32` is gone
in 2.5. The commit before it still builds on upstream's Kotlin 2.3.20.

**Build & publish** (JDK 21):

```bash
cd forks/.worktrees/core-linux-1.12.1
STAGE="$HOME/.m2-fork-refresh/repository" ./publish-embedded.sh   # staging
./publish-embedded.sh                                              # ~/.m2, only when promoting
```

**Running the Linux tests.** macOS cannot execute linuxArm64 test binaries, but Docker on Apple Silicon runs
`linux/arm64` containers natively. Link the test binaries with the embedded sysroot's libraries (fontconfig,
freetype, EGL/GLES, png, expat, bz2, `--allow-shlib-undefined`; an init script that adds these `linkerOpts` to the
`linuxArm64` binaries is enough), then:

```bash
./gradlew --console=plain --no-daemon -I <linker-opts-init-script> \
  :compose:ui:ui:linkDebugTestLinuxArm64 :compose:ui:ui-text:linkDebugTestLinuxArm64 :compose:ui:ui-test:linkDebugTestLinuxArm64
docker run --rm --platform linux/arm64 -v "$PWD/out/compose-multiplatform-core/compose/ui:/t:ro" debian:bookworm bash -c \
  'apt-get update -qq && apt-get install -y -qq libfontconfig1 libfreetype6 libegl1 libgles2 libpng16-16 libexpat1 libbz2-1.0 fonts-dejavu-core >/dev/null;
   /t/ui/build/bin/linuxArm64/debugTest/test.kexe'
```

Results at the refresh: ui 228 passed, ui-test 141, ui-text 61 (1 skipped), foundation 587 passed / 5 failed,
material3 50 passed / 2 failed. The remaining failures are test-environment issues, not product bugs: foundation's
`ScrollableFocusableInteractionTest` expects a platform `Dispatchers.Main`; two material3 tests expect locale-specific
hour cycles and date patterns where Linux uses an explicit en-US / 24-hour fallback.

**Known issues:** the advertised-but-unpublished variants above; Kotlin 2.5 "future error" warnings at
`CarouselState.kt:99` and `LegacyRenderNodeLayer.skiko.kt:389`.

## 2. Compose Multiplatform

**What:** the Compose Gradle plugin and `components-resources`.

**Why:** upstream has no linuxArm64 resource reader. The fork adds runtime resource lookup relative to the executable
(`/proc/self/exe`), a pure-Kotlin XML parser for vector drawables (used by desktop and Linux; Darwin keeps
NSXMLParser), and a Gradle task that copies `compose-resources/` next to each Linux executable
(`syncComposeResourcesForLinuxArm64DebugExecutable`, `…ReleaseExecutable`; the name includes target and binary).

**Embedded target restriction:** `compose.resources.embeddedTargetsOnly=true` limits the components build to metadata,
desktop and linuxArm64 (the core fork publishes nothing else); the default keeps upstream's targets. In that mode the
linuxArm64 publication depends directly on the fork's `*-linuxarm64` coordinates.

**Plugin defaults:** the plugin is published as `1.12.1-embedded-SNAPSHOT` and bakes Compose `1.12.1` and material3
`1.9.0` (the released plugin's values) into `ComposeBuildConfig`. bitchatKmp's forcing replaces the library versions.
The upstream release tag itself still says `compose.version=1.10.1` in `gradle-plugins/gradle.properties`; the fork
sets the real values.

**Build & publish** (plugin first; Koin and bitchatKmp need it):

```bash
cd forks/.worktrees/cmp-linux-1.12.1/gradle-plugins
./gradlew --console=plain --no-daemon --no-configuration-cache -Dmaven.repo.local="$STAGE" publishToMavenLocal
cd ../components
./publish-embedded.sh -Dmaven.repo.local="$STAGE"
```

**Tests:** `:resources:library:desktopTest` (54, including XML namespace inheritance) and the plugin's `:compose:test`.
The components Gradle wrapper is 8.14.3 because KGP 2.5 rejects 8.13.

## 3. Koin

**What:** Koin dependency injection.

**Why:** upstream publishes `koin-core` for linuxArm64 but not `koin-core-viewmodel`, `koin-compose` or
`koin-compose-viewmodel`. Only those four modules are forked and published; every other Koin module resolves upstream
`4.2.2`.

**Changes:** linuxArm64 for the four modules; the Compose-dependent modules are limited to metadata, JVM and
linuxArm64 when `koin.embedded.compose.targets.only=true` (the core fork publishes nothing else); `macosX64`,
`watchosArm32`, `watchosX64` and `tvosX64` removed (Kotlin 2.5 removed them); the ARM tvOS targets stay; stdlib
forcing follows the catalog Kotlin version; the wasmJs `KoinPlatformCoroutinesTools.runBlocking` throws instead of
copying the JS actual, whose `getCompleted()` silently returns a wrong result if the block suspends.

**Build & publish** (the Gradle root is `projects/`; needs `projects/local.properties` with `sdk.dir`):

```bash
cd forks/.worktrees/koin-k25/projects
./gradlew --console=plain --no-daemon --no-configuration-cache -Dmaven.repo.local="$STAGE" \
  -Pkoin.embedded.compose.targets.only=true \
  :core:koin-core:publishKotlinMultiplatformPublicationToMavenLocal :core:koin-core:publishJvmPublicationToMavenLocal :core:koin-core:publishLinuxArm64PublicationToMavenLocal \
  :core:koin-core-viewmodel:publishKotlinMultiplatformPublicationToMavenLocal :core:koin-core-viewmodel:publishJvmPublicationToMavenLocal :core:koin-core-viewmodel:publishLinuxArm64PublicationToMavenLocal \
  :compose:koin-compose:publishKotlinMultiplatformPublicationToMavenLocal :compose:koin-compose:publishJvmPublicationToMavenLocal :compose:koin-compose:publishLinuxArm64PublicationToMavenLocal \
  :compose:koin-compose-viewmodel:publishKotlinMultiplatformPublicationToMavenLocal :compose:koin-compose-viewmodel:publishJvmPublicationToMavenLocal :compose:koin-compose-viewmodel:publishLinuxArm64PublicationToMavenLocal
```

**Tests:** `:core:koin-core:jvmTest` (294 passed, 2 skipped).

## 4. Mosaic

**What:** Jake Wharton's Mosaic, the Compose-runtime terminal UI library behind `:presentation:tui`,
`:apps:embedded-tui` and `:apps:desktop-tui`.

**Why a fork:** upstream already publishes linuxArm64. The fork adds what the Orange Pi's Linux console needs, plus the
owner's wasmJs work: F1–F5 parsed from the Linux console's `ESC [ [ A`..`E`; the cursor hidden even when the terminal
never answers DECRQM; frames written to the tty rather than stdout, so logging cannot corrupt the screen; render only
when the composition is dirty (matters on a Cortex-A53); terminal hardening (signal-safe shutdown, descriptor lifetime,
partial writes, bounded shutdown); wide and zero-width character layout; clipping; OSC 8 hyperlinks; a full-screen
render mode; Shift+Tab; a non-ASCII key fix; and JVM JNI bindings built with Zig for the desktop TUI. Upstream merged
two of the fork's fixes (#1215 nanoTime, #1219 ArcSpline binarySearch), which the 2026-10 rebase dropped from the fork.

**Branch:** `embedded`, on upstream `trunk`. The `wasm-js` branch (published as `0.19.0-wasm-SNAPSHOT` for the owner's
site) is separate and was not touched. Mosaic depends on Google's `androidx.compose.runtime`, not on the Compose fork,
so it must never be linked into `:apps:embedded`.

**Build & publish:** JDK 23 (JDK 21 fails on the fork's `jvmJdk22` source set). The build downloads Zig 0.15.1 itself.
Burst 2.14.0 is required: Burst 2.13.0's compiler plugin crashes `mosaic-tty`'s JVM test compilation on Kotlin 2.4.20
and 2.5.

```bash
cd ../mosaic-wasm   # beside bitchat/, not under forks/
JAVA_HOME=/opt/homebrew/opt/openjdk@23/libexec/openjdk.jdk/Contents/Home \
  ./gradlew --console=plain --no-daemon --no-configuration-cache -Dmaven.repo.local="$STAGE" \
  publishToMavenLocal -PVERSION_NAME=0.19.0-embedded-SNAPSHOT
```

If C sources changed, first re-run `:mosaic-tty:cinteropMosaic<Target>`, `:mosaic-tty:<target>Mosaic` and
`compileKotlin<Target>` with `--rerun`.

**Tests:** JVM tests of `mosaic-runtime`, `mosaic-tty`, `mosaic-terminal`, `mosaic-tty-terminal`; host-native
(`macosArm64Test`) tests of `mosaic-tty` and `mosaic-tty-terminal`; linuxArm64 and wasmJs compilation; `apiCheck`.

**Consumed via:** catalog `mosaic`, declared by `:presentation:tui`, `:presentation:tui:binding` and `:apps:desktop-tui` (`:apps:embedded-tui` gets it through `:presentation:tui`). The desktop TUI profile (`-Ptui.enabled=true`) adds `mavenLocal` restricted to
group `com.jakewharton.mosaic`.

## Skiko

Not forked. `embedded.skikoVersion=0.150.1`, the version Compose 1.12.1 is built against, from Maven Central.

Upstream `skiko-linuxarm64` has bundled an EGL-only Skia since 0.9.47 (JetBrains/skiko #1052), so
`DirectContext.makeGL()` is the EGL path and the old `makeEGL()` fork is retired. The pin used to be 0.9.47 because the
1.10 Compose fork called `org.jetbrains.skiko.ClipboardManager` and `URIManager`, which later Skiko versions removed;
the 1.12 port no longer calls them. Debug and release links of the embedded app report no partial-linkage messages.

## 5. MeshCore

**What:** MeshCore companion firmware for LoRa mesh networking.

**Why:** upstream `linux` support is close, but Orange Pi Zero 3 + SX1276 required additional Linux companion patches.

**Repo & branch:** [fluxxion82/MeshCore](https://github.com/fluxxion82/MeshCore) `orangepi-zero3-sx1276` (based on
`ggodlewski/MeshCore` `linux`).

**Changes (tracked in the fork branch):**
- SX1276 radio support (vs. default SX1262)
- Orange Pi Zero 3 GPIO pin mappings
- SPI device configuration for `/dev/spidev1.1`
- Current PCB template omits reset: header 7/PC9 is shared with the PMIC interrupt
- Linux companion exits nonzero on configuration, GPIO-binding, or radio-init failure rather than spinning in the MCU
  halt loop

See the [current PCB profile](../apps/embedded/docs/ORANGEPI_ZERO3_PCB.md) and [MeshCore setup](meshcore-orangepi-setup.md)
for current pin/runtime configuration. [`MESHCORE_RUNBOOK.md`](../apps/embedded/docs/MESHCORE_RUNBOOK.md) preserves
earlier patch history with a reset-mapping correction.

**Build (on-device):**

```bash
cd ~/meshcore-linux
FIRMWARE_VERSION=dev ./build.sh build-firmware linux_companion_sx1276
# Deploy only after stopping the app and both radio owners; preserve the prior binary.
```

## 6. Meshtastic Firmware

**What:** meshtasticd native firmware for Linux LoRa devices.

**Why:** debug logging and error handling improvements for the RF95/SX1276 SPI interface on Orange Pi Zero 3.

**Repo & branch:** [fluxxion82/firmware](https://github.com/fluxxion82/firmware) `orangepi-rfm95w`

**Changes:**
- `src/mesh/RF95Interface.cpp` — RF95 init/reconfigure hardening for Portduino
- `src/mesh/RadioLibRF95.cpp` — init sequence logging and Portduino write-failure tolerance
- `CUSTOM_CHANGES.md` — full documentation of changes, known issues (RF95 init -20, IRQ flood, invalid pointer crash),
  and configuration
- `orangepi/runtime-captures/` — snapshots of Pi-only runtime/dependency patches (`LinuxGPIOPin.cpp`, `SX127x.cpp`) so
  ad-hoc changes are not lost

The current PCB profile omits `Lora.Reset` in every effective YAML source and retains the reset prestart hook as a
no-op. Do not replace preserved Pi dependency patches merely to change pin configuration; reset omission is supported
by the existing parser.

See the "Need source build for patched behavior" section in [`meshtastic-orangepi-setup.md`](meshtastic-orangepi-setup.md)
for build steps.

**Build (on-device):**

```bash
cd ~/firmware
source ~/meshtastic-venv/bin/activate
pio run -e native
# Deploy only after stopping the app and both radio owners; preserve the prior binary.
```

## 7. gattlib

**What:** the BLE GATT client library used for the Central role on the embedded target. Unlike the other entries this
is a git submodule, not a `forks/` checkout: `data/remote/transport/bluetooth/native/gattlib`, pinned by SHA, so
`.gitmodules` and the submodule pointer are the whole mechanism.

**Why:** gdbus-codegen cached-property getters return `NULL` once a peer's BlueZ objects have gone away mid-discovery,
and gattlib dereferenced four of them — `gattlib_string_to_uuid()` passes its argument to `strlen()`, the flags loop
dereferences the array head, and `gattlib_discover_char_range()` hands the Device property to `strcmp()`. The library
holds its own recursive mutex for the length of a discovery call, so this cannot be guarded from Kotlin.

**Repo & branch:** [fluxxion82/gattlib](https://github.com/fluxxion82/gattlib) `bitchat-null-guards`, branched from
upstream `labapart/gattlib` @ `1580056`.

**Changes:**
- `dbus/gattlib.c` (the compiled `BLUEZ_VERSION >= 5.38` branch only) — NULL guards on the four property getters;
  `g_clear_error()` in place of `g_error_free()` so a freed `GError` is not read again on the next iteration;
  `g_object_unref()` on the skip paths that leaked a proxy.
- `dbus/gattlib.c`, connection teardown (`8482263`) — BlueZ can complete a connection whose `Connect()` call it has
  already failed; the stale property handler then ran the success path against an attempt whose object path had
  been freed. The fork finishes tearing the failed attempt down, ignores a signal arriving for an abandoned attempt
  and NULLs `dbus_objects` after freeing it.

**Behaviour change:** discovery now returns fewer entries where it used to crash. A peer missing the bitchat
characteristic is already abandoned by `BlueZGattClientService.discoverCharacteristics()`, which is the correct
outcome — the scanner re-offers it under the existing backoff.

**Rebuild:**

```bash
docker run --platform linux/amd64 --rm \
  -v "$PWD/data/remote/transport/bluetooth/native:/build" \
  bitchat-linux-arm64-cross bash /build/build-gattlib-linux-arm64.sh
```

Measured at **23 s**, and byte-reproducible: two consecutive builds of the same source produce identical archives. So
unlike Arti and libsodium this one is safe to rebuild casually. Note that `build-gattlib-linux-arm64.sh:43` does
`rm -rf` on the build directory, so copy the existing `libgattlib.a` aside first if you want a guaranteed rollback.

**Restoring the patch if the submodule is reset.** The change lives in a commit on the fork, so
`git submodule update --init data/remote/transport/bluetooth/native/gattlib` restores it from `.gitmodules`. If the
submodule is ever pointed back at `labapart/gattlib`, recover with:

```bash
cd data/remote/transport/bluetooth/native/gattlib
git remote set-url origin https://github.com/fluxxion82/gattlib.git
git fetch origin bitchat-null-guards
git checkout 848226332b998ecb467b19f321b8156660b05602
```

## Staging repository and promotion

Fork artifacts are first published into an isolated Maven repository, `~/.m2-fork-refresh/repository`, by passing
`-Dmaven.repo.local=…` (Gradle's `mavenLocal()` and `publishToMavenLocal` both honour it; add
`--no-configuration-cache`). bitchatKmp is verified against it the same way:

```bash
GRADLE_ARGS="-Dmaven.repo.local=$HOME/.m2-fork-refresh/repository --no-configuration-cache" scripts/verify.sh full
```

Promoting into `~/.m2/repository` is a deliberate step, done together with merging the matching bitchatKmp change,
because Kotlin 2.5-built klibs cannot be read by a build still on Kotlin 2.4. The new version names do not overwrite
the old `9999.0.0-SNAPSHOT` / Koin `4.2.2` artifacts; only Mosaic's `0.19.0-embedded-SNAPSHOT` is replaced. Archive
whatever promotion overwrites first, merge (not replace) artifact-root `maven-metadata-local.xml` files, and keep
the staging repository and archives until the result has been proven on the device.

Before promotion, the embedded binaries must also pass `scripts/embedded-smoke.py` on a board: link gates cannot
see run-loop bugs (the 1.12 port briefly dropped the main-dispatcher pumping and every link still
succeeded).

## First-time setup

```bash
cd bitchat/forks
git clone https://github.com/fluxxion82/compose-multiplatform-core.git && git -C compose-multiplatform-core switch linux-1.12.1
git clone https://github.com/fluxxion82/compose-multiplatform.git && git -C compose-multiplatform switch linux-1.12.1
git clone https://github.com/fluxxion82/koin.git && git -C koin switch sa_linux_4.2.2-kotlin-2.5
cd ../..   # workspace/multiplatform
git clone https://github.com/fluxxion82/mosaic.git mosaic-wasm && git -C mosaic-wasm switch embedded
```

Publish in this order: core → Compose Gradle plugin → compose-resources and Koin → Mosaic (independent). Then create
the embedded sysroot ([embedded README](../apps/embedded/README.md), step 1) and verify:

```bash
./gradlew -Pembedded.enabled=true :apps:embedded:linkDebugExecutableLinuxArm64 --console=plain
```

## Rollback

The pre-refresh branches are untouched: core `linux-1.10.0`, Compose Multiplatform `release/1.10`, Koin
`sa_linux_4.2.2`; Mosaic's previous `embedded` is at tag `archive/embedded-2026-10-02`. Histories before the commit
consolidation are kept at local tags `archive/*-pre-squash-2026-10-02`. The pre-refresh `~/.m2` fork artifacts are in
`~/.m2-fork-refresh/m2-fork-backup-2026-10-02.tgz` with hash inventories beside it. Because the new versions have new
names, rolling bitchatKmp back is a matter of reverting its version properties to `9999.0.0-SNAPSHOT` / `4.2.2` (plus
restoring Mosaic's old artifacts).

## Next Kotlin bump

1. In each fork, add one commit on top that changes only the Kotlin version (and whatever the compiler forces), keeping
   it last. Order: core → Compose Multiplatform (plugin, then components) → Koin; Mosaic is independent.
2. Publish everything into a fresh staging repository and run bitchatKmp's `verify.sh full` against it with the
   staging arguments above.
3. Prove the embedded binaries on a board, then promote.

Rebuild every fork for each new Kotlin release. Do not assume that klibs built by a Beta compiler stay readable by the
stable one.

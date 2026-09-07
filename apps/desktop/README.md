# `:apps:desktop` — JVM desktop app

Compose Multiplatform desktop application (`com.bitchat.desktop.AppKt`). macOS is the verified host;
Linux builds and runs but has never been shipped, and Windows is untried.

## Run

```bash
./gradlew :apps:desktop:run --console=plain                            # no native prerequisites
./gradlew :apps:desktop:run -PbleNative=macos -PlocationNative=macos   # macOS-only native BLE/location
```

`-PbleNative=macos` / `-PlocationNative=macos` build Kotlin/Native dylibs and bundle them as classpath
resources; `NativeBleLoader` / `NativeLocationLoader` extract and `System.load` them, and both no-op on a
non-macOS host. Without them the app uses the desktop BLE stubs and IP-based location.

## Native libraries and app resources

The Arti (Tor) JNI library is optional and is **not** found through an absolute build-directory path.
`data/remote/tor/native/build-desktop.sh` writes `libarti_desktop.{dylib,so,dll}` into
`data/remote/tor/native/libs/desktop/`; the `stageAppResources` task copies whatever is there into the
Compose app-resources layout under `build/bitchatAppResources/`, splitting by extension into the
per-OS directories that layout provides (`macos/`, `linux/`, `windows/`) so a host that has built
Arti for more than one OS does not ship all of them in every package. `nativeDistributions.appResourcesRootDir`
points at that root; Compose's `prepareAppResources` flattens `common/` plus the build host's own two
directories (`<os>/` and `<os>-<arch>/`) into a single staging directory and hands it to the app as
`-Dcompose.application.resources.dir` — `build/compose/tmp/prepareAppResources` for `run`, and
`$APPDIR/resources` inside an installed package. So the library is always at the top level of that
directory at runtime, whichever per-OS directory it was staged from. `TorManager.jvm.kt` loads it from
there, falling back to `System.loadLibrary` for hosts that put it on `java.library.path` themselves. If
the library is missing, Tor reports itself unavailable and the rest of the app runs.

### What the app shows when Tor is unavailable

`TorManager.isAvailable` is false whenever the library did not load, and that answer is carried through
`TorRepository.torAvailability()` and the `GetTorAvailability` use case into `SettingsState.torAvailability`
(with `SettingsState.torAvailable` as the boolean convenience). In the settings screen that means:

- the **Tor Network** switch is disabled, and stays off - `TorRepo.enable()` only persists `TorMode.ON`
  once the manager has actually started, so the switch cannot be left on for a Tor that can never run;
- the actionable reason is printed under the switch **and** in the Tor status card, from the first
  frame, without the user touching anything: because the switch is disabled, `TorManager.start()` can
  never be reached from the UI, so `TorManager.jvm.kt` seeds it into the initial `TorStatus.errorMessage`
  rather than waiting for a start that will not happen. It reads e.g. `Tor unavailable: native library
  libarti_desktop.so not found in compose.application.resources.dir=... or on java.library.path=...
  Build it with data/remote/tor/native/build-desktop.sh, or leave Tor off.` Without that seeding the
  screen falls back to the generic `tor not available in this build`, which names nothing actionable;
- Nostr relay traffic falls back to a direct connection and says so. Relay log lines only claim Tor when
  the socket really went through the SOCKS proxy: the claim is made from the proxy state read before the
  connect **and** re-read when the socket is reported open, since the engine picks the proxy in between
  (`claimsTorRoute`). They never claim Tor at all on iOS or on the embedded `linuxArm64` build, whose
  Darwin and Curl engines ignore the proxy entirely (`httpEngineSupportsTorProxy`). This desktop app is
  **not** in that group on any OS, macOS included: it is a `kotlin("jvm")` application on the OkHttp
  engine, which is the one engine that installs a `ProxySelector`, so Tor genuinely proxies here.

## Packaging

```bash
./gradlew :apps:desktop:packageDmg   # macOS host
./gradlew :apps:desktop:packageDeb   # Linux host
./gradlew :apps:desktop:packageRpm   # Linux host, needs rpm-build
./gradlew :apps:desktop:packageMsi   # Windows host
```

jpackage only produces host-OS formats, so **each package must be built on its target OS**. There is no
cross-packaging path, and `compose.desktop.currentOs` in this module (and in `:presentation:design` /
`:presentation:screens`) resolves the Skiko native runtime for the build host, so a macOS-built jar will
not render on Linux either.

`-Pcompose.desktop.packaging.checkJdkVendor=false` is a **macOS-only** workaround for `packageDmg` on a
Homebrew JDK. Linux packaging never needs it.

### Fedora / RPM

```bash
sudo dnf install -y rpm-build          # required for packageRpm
./gradlew :apps:desktop:packageRpm --console=plain
```

Debian/Ubuntu need `fakeroot` for `packageDeb`.

### Linux runtime requirements

Skiko (Compose Desktop's rendering backend) declares `GL`, `X11` and `fontconfig` as dynamic system
libraries, so they must be present even for software rendering:

```bash
sudo dnf install -y mesa-libGL libX11 fontconfig       # required
sudo dnf install -y libsecret gnome-keyring            # secure storage (see below)
```

A missing `libGL.so.1` surfaces as `Skiko RenderException: Cannot create OpenGL context`.

- **X11 only.** Skiko has no Wayland backend, so a pure Wayland session needs XWayland.
- **Software rendering fallback:** the Linux default is OpenGL with an automatic software fallback;
  force it with `-Dskiko.renderApi=SOFTWARE` when the GPU path misbehaves.
- **An OS keyring is required, not optional.** Secure storage uses
  `com.microsoft:credential-secure-storage`, which needs libsecret installed *and* an unlocked default
  collection reached over the session D-Bus - in practice a real desktop session with gnome-keyring or
  KWallet unlocked. Headless boxes, plain SSH sessions and containers have no session bus, so no
  backend qualifies. This app holds the master key for identity keys, the block list and preferences
  there and has no unencrypted fallback, so in that state it refuses to start **by design**, with an
  explicit message rather than an NPE. The same applies on macOS (login Keychain) and Windows
  (Credential Manager). The guarantee is this JVM desktop build's: the `linuxArm64` embedded build
  uses a different `actual` and writes the same preferences as plain-text files under
  `~/.bitchat/prefs`.
- **LoRa** over `/dev/ttyUSB*` needs the user in the `dialout` group.
- There is **no Linux JVM BLE backend**; the mesh transport reports itself unavailable.

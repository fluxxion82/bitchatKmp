# Desktop terminal UI

The JVM terminal app runs on your desktop and shares the Compose desktop app's identity,
preferences, and Tor state. Close Compose before starting it: only one desktop app may run at a
time, enforced by `~/.bitchat/desktop.lock`.

## Build and launch

Build the required Tor library and launch with one command in a real terminal:

```bash
scripts/run-desktop.sh tui
```

The launcher exits at once if its input is not a terminal, then runs
`data/remote/tor/native/build-desktop.sh --install`, `installDist`, and the installed launcher. The Tor build runs
on every start and needs `rustc` with the toolchain pinned in `data/remote/tor/native/RUST_TOOLCHAIN`, the Arti
submodule checked out exactly at the commit in `data/remote/tor/native/ARTI_VERSION`, and a few GB free under
`~/.cache/bitchat-arti`; the first run is slow. It passes no Gradle properties through, so the macOS native BLE
and location options are not applied.

Close Compose first. For separate build and launch steps:

```bash
./gradlew -Pembedded.enabled=false -Ptui.enabled=true :apps:desktop-tui:installDist --console=plain
```

Then run the generated launcher in Terminal or another real terminal:

```bash
apps/desktop-tui/build/install/bitchat-tui/bin/bitchat-tui
```

Mosaic opens the controlling terminal, so use the installed launcher rather than a Gradle `run`
task or an IntelliJ Run console. IntelliJ can build `installDist`; its Terminal tab can run the
launcher. To check the build without starting the app:

```bash
apps/desktop-tui/build/install/bitchat-tui/bin/bitchat-tui --version
```

It prints `bitchat-tui <version> (<git sha>)`. The Settings screen does not show this identity yet.

## Tor

`installDist` bundles the desktop JNI library from `data/remote/tor/native/libs/desktop` into
`lib/native`. It does not build Arti. To build and install the pinned desktop library explicitly:

```bash
data/remote/tor/native/build-desktop.sh --install
```

Run `installDist` again after installing a new library, then fully restart the app. A native build
without `--install` leaves its result outside the repository and does not update either desktop
app. Tor can be toggled in Settings. Logs are written to `~/.bitchat/desktop-tui.log` (mode 0600,
rotated at 5 MB).

## Embedded TUI

The ARM64 board app is a separate native executable. Its build and deployment commands are in
[Embedded terminal UI deployment](../embedded-tui/README.md). The desktop launcher above does
not launch or deploy that app.

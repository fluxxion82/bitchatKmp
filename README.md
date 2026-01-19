# Bitchat KMP

Kotlin Multiplatform rewrite of Bitchat.
- https://github.com/permissionlesstech/bitchat
- https://github.com/permissionlesstech/bitchat-android

I used the same protocol + transports - Bluetooth mesh, Tor/nostr, geohash channels. interoperates with legacy Android/iOS builds.

## Getting Started

After cloning, initialize the git submodules:
`git submodule update --init --recursive`

This pulls in the native library sources:
- `data/noise/native/noise-c` - Noise protocol (C)
- `data/crypto/native/libsodium` - Libsodium crypto
- `data/crypto/native/secp256k1` - Bitcoin secp256k1 curves
- `data/remote/tor/native/arti` - Tor/Arti client (Rust)

Then build the native libraries for your target platform(s) - see [Native library builds](#native-library-builds).

## Project layout (shared modules)
- `domain`: business logic contracts and models.
- `data:*`: implementations, including crypto, cache, noise (native), Tor client, nostr, REST client, and transports (Bluetooth + platform).
- `presentation:*`: shared design system, screens, view models/VOs.
- `apps:*`: platform-specific app entry points (`desktop`, `droid`, `iosApp`).
- `iosdi`: shared KMP framework for the iOS app.

## Native library builds
- All platforms: `./scripts/build-all-platforms.sh`
- Android only: `./scripts/build-all-android.sh`
- Desktop (macOS) only: `./scripts/build-all-desktop.sh`
- iOS only: `./scripts/build-all-ios.sh`
Underlying scripts live in `data/noise/native`, `data/crypto/native` (libsodium, secp256k1), and `data/remote/tor/native`.

## Run by platform
- **Android:** `./gradlew :apps:droid:installDebug`
- **Desktop:**
  Currently only supporting MacOS.
  - macOS BLE: `./gradlew :apps:desktop:clean :apps:desktop:run -PbleNative=macos --rerun-tasks`
  - In order to allow for location permissions one will probably have to build an .app bundle but you can specify the desktop app to use the mac os location services with:: `./gradlew :apps:desktop:clean :apps:desktop:run -PbleNative=macos -PlocationNative=macos --rerun-tasks`
- **iOS:** run the Xcode project `apps/iosApp/iosApp.xcodeproj`

## Submodules and native bits
- `data:remote:transport:bluetooth`: native BLE bindings; required for Android + Desktop (macOS optional flag `-PbleNative=macos` bundles dylib).
- `data:local:platform`: platform services (e.g., macOS location) bundled when `-PlocationNative=macos`.
- `data:remote:tor`: Tor/Arti client native libs copied into desktop runtime automatically via Gradle config.
- `data:noise`, `data:crypto`: native crypto (Noise protocol, secp256k1).

## Notes
- For macOS desktop native packaging, rerun with `--rerun-tasks` when toggling `-PbleNative` or `-PlocationNative` so Gradle copies fresh dylibs.

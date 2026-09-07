# bitchatKmp Architecture Summary

This document summarizes the `bitchatKmp` project organization for comparison with other Kotlin Multiplatform projects. The useful architectural signal is the separation between domain contracts, infrastructure implementations, presentation state/UI, and thin platform app shells.

## High-Level Shape

`bitchatKmp` is a Kotlin Multiplatform rewrite of Bitchat. The codebase follows a Clean Architecture-style module layout:

- `domain`: pure business contracts, models, use cases, event buses, and repository interfaces.
- `data:*`: concrete infrastructure, persistence, crypto, transport, remote client, Tor, and repository implementations.
- `presentation:*`: UI state objects, shared viewmodels, shared Compose design components, image picker, and navigation/screens.
- `apps:*`: platform applications that assemble dependencies and launch UI/runtime services.
- `iosdi`: KMP framework/DI bridge for the iOS app.
- `firmware`, `scripts`, `docker`, and `docs`: hardware, native dependency, embedded, and developer support.

The dependency direction is mostly inward:

```text
apps:* -> presentation:* -> domain
apps:* -> data:* -> domain
presentation:viewmodel -> domain + presentation:viewvo
presentation:screens -> presentation:design + presentation:viewmodel + presentation:viewvo + domain
data:repo -> domain repository interfaces + lower-level data modules
```

The app modules are composition roots. They depend on many modules directly because they assemble the Koin graph and platform runtime. The domain module does not depend on project modules.

## Included Gradle Modules

Modules are declared in `settings.gradle.kts`:

| Area | Modules | Role |
| --- | --- | --- |
| Apps | `:apps:droid`, `:apps:desktop`, optional `:apps:embedded` | Platform entry points and dependency graph assembly. |
| Domain | `:domain` | Business use cases, domain models, repository interfaces, event bus contracts, and common DI. |
| Data core | `:data:cache`, `:data:crypto`, `:data:local:platform`, `:data:mediautils`, `:data:noise`, `:data:repo` | Cache primitives, crypto/noise protocol bindings, platform services, media helpers, and repository implementations. |
| Remote REST | `:data:remote:rest:client`, `:data:remote:rest:dto` | Ktor clients, websocket clients, DTOs, API errors, and mapping. |
| Remote transport | `:data:remote:transport`, `:data:remote:transport:bluetooth`, `:data:remote:transport:nostr`, `:data:remote:transport:lora`, `:data:remote:transport:lora:bitchat`, `:data:remote:transport:lora:meshtastic`, `:data:remote:transport:lora:meshcore` | Transport abstractions and concrete mesh, Nostr, Bluetooth, and LoRa protocol implementations. |
| Privacy/network | `:data:remote:tor` | Tor/Arti integration and platform-specific native setup. |
| Presentation | `:presentation:viewvo`, `:presentation:viewmodel`, `:presentation:design`, `:presentation:design:imagepicker`, `:presentation:screens` | View state DTOs, viewmodels, reusable Compose UI, image picking, and navigation/screens. |
| iOS bridge | `:iosdi` | Shared framework and DI setup consumed by iOS. |

The embedded profile is opt-in: `embedded.enabled` defaults to `false` in `gradle.properties`, and passing `-Pembedded.enabled=true` (or setting it in `~/.gradle/gradle.properties`) turns on Linux ARM64 targets and includes `:apps:embedded`. Embedded builds use forked Compose/Koin/lifecycle artifacts published to `~/.m2` where linuxArm64 support is needed (see `docs/FORKED_LIBRARIES.md`).

## Domain Layer

`domain` is organized by feature package rather than by technical type:

- `app`: app theme, background mode, battery optimization, clearing data.
- `chat`: mesh/channel/private chat use cases and chat models.
- `connectivity`: connection event models and repository contracts.
- `location`: geohash/location channels, notes, bookmarks, permissions, participants.
- `lora`: LoRa settings and protocol selection use cases.
- `nostr`: Nostr settings/events/repository contract.
- `tor`: Tor mode/status use cases and event bus.
- `user`: profile, nickname, blocking, favorites, persisted user state.
- `initialization`: app startup initializer contract and orchestration.
- `base`: `Usecase`, `Outcome`, `Failure`, coroutine context/scope facades.
- `di`: `domainModule`.

Domain code defines repository interfaces such as `ChatRepository`, `UserRepository`, `LocationRepository`, `NostrRepository`, `TorRepository`, `LoRaSettingsRepository`, and `ConnectivityRepository`. Use cases are small classes named as actions or queries, for example `SendMessage`, `ObserveChannelMessages`, `SetAppTheme`, `EnableTor`, `GetLoRaSettings`, and `SaveUserStateAction`.

Eventing is explicit and feature-scoped. Domain defines event bus interfaces and in-memory implementations for app, chat, foreground, location, Nostr, Tor, and user events. Many use cases depend on repositories plus event buses, which keeps viewmodels from directly coordinating infrastructure details.

## Data Layer

The data layer is split by infrastructure concern and protocol boundary.

`data:repo` is the main adapter from domain interfaces to infrastructure. It binds domain repository contracts to implementations such as `AppRepo`, `ChatRepo`, `UserRepo`, `LocationRepo`, `NostrRepo`, `TorRepo`, `BlockListRepo`, and `LoRaSettingsRepo`. It also contributes `AppInitializer` implementations for Nostr, Tor, Bluetooth mesh, and LoRa.

`data:cache` provides named in-memory/cache infrastructure used by repositories and transports, including caches for geohash aliases, conversations, and relay info.

`data:local:platform` contains platform-local services such as settings, location, connectivity, geocoding, preferences, resource reading, and background service support. It uses `expect`/`actual` style Koin modules like `commonLocal` plus platform-specific `localModule`.

`data:crypto` and `data:noise` isolate cryptography and Noise protocol concerns. JVM/Android can use Maven dependencies, while iOS/macOS/Linux ARM64 use native libraries such as libsodium, secp256k1, and noise-c via cinterop.

`data:remote:rest:client` and `data:remote:rest:dto` isolate remote client mechanics and wire models. The client module includes Ktor HTTP/websocket clients, Nostr relay clients, retry/network logging concerns, and API error modeling. DTOs are kept separate so transports/repositories can share wire types without depending on client internals.

`data:remote:transport` is the base transport abstraction module. Concrete transports live in submodules:

- `bluetooth`: BLE mesh transport, with Android, iOS, desktop, macOS-native, and linuxArm64/BlueZ variants.
- `nostr`: Nostr transport backed by REST/websocket clients, crypto, cache, and Noise.
- `lora`: shared LoRa abstractions and settings/cache support.
- `lora:bitchat`: Bitchat LoRa protocol implementation.
- `lora:meshtastic`: Meshtastic interoperability implementation.
- `lora:meshcore`: MeshCore interoperability implementation.

`data:remote:tor` wraps Tor/Arti integration with platform-specific native setup. Desktop apps pass native library paths through JVM args.

## Presentation Layer

The presentation layer is deliberately split into view data, logic, reusable UI, and screen orchestration.

`presentation:viewvo` contains plain view-state/value objects used by viewmodels and UI. Examples include chat state, DM state, header state, settings state, permission status/type, and location state.

`presentation:viewmodel` contains lifecycle-aware shared viewmodels. Viewmodels depend on domain use cases and `presentation:viewvo`; they do not own repository implementations. Viewmodels expose state/effects and invoke use cases in `viewModelScope`. The module has its own Koin `viewModelModule`.

`presentation:design` is the shared Compose design system and component library. It contains reusable chat, location, permissions, battery, settings, icons, media, mapping, error, and core UI components. It depends on `domain`, `viewvo`, `data:mediautils`, and `presentation:design:imagepicker`.

`presentation:design:imagepicker` isolates image picking and media helpers behind a small design-adjacent module. This keeps optional/platform media behavior out of the general design module.

`presentation:screens` contains navigation and feature screens. `BitchatGraph` wires routes to screens and obtains viewmodels via Koin. Platform-specific source sets provide implementations for permissions, Bluetooth status, location behavior, and platform detection.

The resulting presentation dependency direction is:

```text
viewvo <- viewmodel <- screens
design <- screens
design:imagepicker <- design
domain <- viewmodel/design/screens
```

## App Modules and Composition Roots

App modules are intentionally thin shells that assemble the same shared application out of platform-specific services.

`apps:droid` is a standard Android application. `BitchatApplication` starts Koin with app/build modules plus shared modules: `commonRepoModule`, `domainModule`, `commonLocal`, `localModule`, `clientModule`, `viewModelModule`, `bluetoothModule`, `nostrModule`, `torModule`, LoRa protocol modules, and the LoRa protocol manager. `MainActivity` hosts the Compose UI and obtains `MainViewModel` through Koin.

`apps:desktop` is a Compose Desktop JVM app. It uses the same shared presentation/data/domain modules, adds desktop packaging, JVM args for native Tor/Chromium behavior, and optional native BLE/location loading through Gradle properties such as `-PbleNative=macos` and `-PlocationNative=macos`.

`apps:embedded` is an optional Kotlin/Native linuxArm64 app for Orange Pi-style devices. It depends on the same core modules but also handles DRM/GBM/EGL rendering, touch, keyboard, and LoRa protocol selection. It is gated by `-Pembedded.enabled=true` (off by default).

`apps/iosApp` is the native iOS app folder. `iosdi` exposes shared Kotlin DI/framework wiring to iOS and includes domain, data, presentation, and app-facing modules.

## Dependency Injection Pattern

Koin is the cross-platform dependency injection mechanism.

Common modules:

- `domainModule`: registers coroutine facades, event buses, and use cases.
- `commonRepoModule`: binds domain repository interfaces to data repository implementations and registers startup initializers.
- `commonLocal` / `localModule`: shared and platform-specific local services.
- `clientModule`: remote client services.
- `viewModelModule`: shared viewmodel registrations.
- `bluetoothModule`, `nostrModule`, `torModule`, LoRa modules: protocol/infrastructure modules.

Platform entry points own final graph assembly. This keeps shared code reusable while allowing each app to add platform-specific build config, native bindings, app context, and protocol defaults.

## Kotlin Multiplatform Source Set Strategy

Most modules use `commonMain` for shared logic and then add platform source sets only where necessary:

- `androidMain`: Android-specific services, permissions, BLE, local storage, media, and Compose integrations.
- `jvmMain` / `desktopMain`: desktop/JVM services, JNA/serial support, Swing coroutine dispatcher, desktop Compose dependencies.
- `iosMain` / `appleMain` / `macosMain`: iOS/macOS native implementations and cinterop.
- `linuxMain` / `linuxArm64Main`: embedded Linux, BlueZ, SPI, DRM/GBM/EGL, touch, and hardware support.

The project uses `applyDefaultHierarchyTemplate()` broadly, then creates custom intermediate source sets where needed, such as `desktopMacMain` for macOS desktop BLE sharing.

## Naming and Organization Conventions

Useful conventions to copy:

- Module names encode layer and capability: `data:remote:transport:lora:meshtastic`, `presentation:design:imagepicker`, `data:local:platform`.
- Domain packages are feature-first: `chat`, `location`, `user`, `tor`, `lora`, not `usecases` or `models` at the root.
- Use cases are verb phrases: `SendMessage`, `JoinChannel`, `ObserveNotes`, `GetTorStatus`, `SetLoRaEnabled`.
- Repository contracts live in `domain/<feature>/repository`; implementations live in `data:repo`.
- Event contracts live in `domain/<feature>/eventbus`; in-memory event buses are registered in domain DI.
- View state classes live in `presentation:viewvo`, separate from viewmodels and Composables.
- Platform-specific behavior is handled with KMP source sets and `expect`/`actual`-style modules rather than conditionals scattered through shared code.
- App modules are composition roots and runtime launchers, not business logic homes.

## Architectural Strengths to Reuse

The strongest transferable ideas are:

- Keep the domain module independent and centered on feature packages, use cases, models, repository interfaces, and event buses.
- Put concrete repository implementations in a dedicated `data:repo` module that adapts many infrastructure modules into domain contracts.
- Separate reusable UI components (`presentation:design`), screens/navigation (`presentation:screens`), viewmodels (`presentation:viewmodel`), and view-state DTOs (`presentation:viewvo`).
- Make platform apps thin composition roots that assemble shared modules through Koin.
- Split protocol/transport implementations into independently named submodules so optional capabilities like Bluetooth, Nostr, Tor, and LoRa protocols remain replaceable.
- Use KMP source sets for platform differences and keep the common source set as the primary home for business and presentation logic.
- Gate special targets such as embedded Linux behind Gradle properties so ordinary Android/Desktop/iOS work is not burdened by native/hardware requirements.

## Cautions and Tradeoffs

The architecture is powerful but module-heavy. A smaller project should copy the layering and dependency direction before copying every module boundary.

Some modules have pragmatic cross-layer dependencies. For example, `presentation:design` depends on `domain` and `data:mediautils`, and app modules depend directly on both presentation and data modules. This is reasonable for a KMP app with platform composition roots, but a stricter architecture might isolate UI components from domain types more aggressively.

`data:repo` is a central integration point and carries many dependencies. That makes app assembly simpler, but it can become a large coordination module. If copied into a new project, keep repository implementations feature-grouped and avoid letting `data:repo` become a place for unrelated business logic.

The embedded target adds significant build complexity through forked dependencies and native libraries. Treat that pattern as optional unless the target project also needs hardware/native support.

---
title: Clean architecture review — what drifted
date: 2026-10-12
commit: 389fe0a
tags: [bitchatkmp, architecture, review, domain, clean-architecture, agent-drift]
status: findings, corrected 2026-10-12
related:
  - "[[2026-10-12-clean-architecture-plan]]"
  - "[[2026-10-12-domain-usecase-inventory]]"
---

# Clean architecture review — what drifted

- **Date:** 2026-10-12, at `389fe0a`.
- **Trigger:** most recent work was done by AI agents and not fully reviewed. The owner saw `Observe*` use cases and did not recognise the naming, and asked for a review of the whole architecture with the domain module first.
- **Intended design** (from `docs/architecture-summary.md` and the owner): Clean Architecture. `domain` = models, use cases named *verb + domain model* implementing `Usecase<TParam, TResult>`, repository and data-store interfaces. `data:*` implements them. `presentation:*` reaches the domain only through use cases from viewmodels. `apps:*` are thin Koin composition roots. Transports and libraries are plugins that can be swapped.
- **Scope:** findings only. Nothing in the tree was changed. The plan is in [[2026-10-12-clean-architecture-plan]]; the per-use-case table is in [[2026-10-12-domain-usecase-inventory]].
- **Method:** one pass over the module graph and the domain tree by hand, then four read-only review agents in parallel (use cases; models and contracts; layering and plugins; `data:repo`), then spot checks of every claim that would change the plan if it were wrong. Line numbers are at `389fe0a`. Paths: `D/` = `domain/src/commonMain/kotlin/com/bitchat/domain/`, `R/` = `data/repo/src/commonMain/kotlin/com/bitchat/repo/`, `VM/` = `presentation/viewmodel/src/commonMain/kotlin/com/bitchat/viewmodel/`.

## Corrections after the owner's review (2026-10-12)

Read these before anything below; they override the sections they name.

- **"Navigation lives in the domain" (§2) is withdrawn.** `UserState` and `ActiveState` are domain app states that the UI maps to screens; that is the owner's design, and states lining up with screens does not make them navigation. What remains of §2 is the size of `SaveUserStateAction` (8 dependencies) and its repeat of `GetUserState`'s permission gating.
- **Bug 5 (§12, `LocationRepo` and the Tor gate) is not a leak.** Relay websocket connections take their route from `RouteAwareClientProvider`, which throws `TorRouteRequiredException` when Tor is requested and not ready. `ChatRepo`'s gate only waits up to 30 s before that point; `LocationRepo` fails at once.
- **Bug 9 (§12, discarded `verifyPasswordOwnership` result) is not a bug.** The call is made for its side effect: on a match it records the user as a verified owner, caches the key and emits `ChannelOwnershipVerified`.
- **Bug 10 (§12, iOS initializer override) is latent.** Swift passes one initializer today.
- **Bug 7 (§12, scope facade registered as `factory`) has no visible effect today.** Nothing cancels those scopes.
- **`ObserveMeshChannelPeople` does rethrow cancellation** (§2 and §7 said otherwise). Only `JoinChannel` and `SetChannelPassword` swallowed it.
- **iOS dispatchers (§5, bug 1), refined.** The Apple Bluetooth services launch characteristic writes, notifies and connection callbacks on `applicationScope`, whose context is the facade's `default`. The fix moves only `io` off the main queue and keeps `default` on it until a device shows CoreBluetooth tolerates parallel calls.
- **Event buses (§6): the owner keeps them, and the `GetJoinedChannels` pattern is the intended design.** Read on subscribe plus `replay = 1` costs at most one extra read, when the replayed event passes the filter; it closes the gap between the first read and the subscription (the comment in `ObserveMeshChannelPeople` says so). It is not a defect. The bus problems that stand: the Nostr bus was a rendezvous channel (fixed), `ChannelJoined` / `ChannelListUpdated` are emitted from both layers, viewmodels subscribe to buses directly, and an emitter waits for the slowest subscriber (not traced to a real hang). Observation is not one-size-fits-all.
- **Decisions taken since are in `planning/DECISIONS.md`;** the work in flight is in `planning/STATUS.md`.

## Corrections after the local review (2026-10-12)

From local-claude and Codex, each checked in the code. Evidence and the full list: [[2026-10-12-local-review]], section 2.

- **iOS dispatchers (§5, bug 1).** Keeping `default` on the main queue does not keep Bluetooth work serial: CoreBluetooth already calls back on other queues. Moving `io` exposes collections that only the single queue kept safe, and drives `IosLocationService` from a worker. The fix is not in the first slice; it is on `claude/apple-io-off-main`.
- **Event buses (§6).** `ChannelJoined` / `ChannelListUpdated` are not emitted twice: the use case publishes them when joining an existing channel, the repository when creating one. And read on subscribe plus `replay = 1` closes the start-up gap only for `ObserveMeshChannelPeople`, which treats the first event as "everything changed"; a filtered observer can still miss an event that a later, unrelated one replaced.
- **Missing filters (§1, §6).** Only `GetAppTheme` misses one it could have. `NostrEvent` has a single variant, and `LocationEvent.NotesChanged` carries no geohash to filter by.
- **Tor gate (the correction to bug 5).** `LocationRepo` does not fail at once: `RouteAwareClientProvider` waits for a ready route as well, 30 s by default. Both fail closed there.
- **`TorRepo` and `MutableRequestedTorIntent` (§8).** Not a violation. The port allows a user action or an explicit reset, and `TorRepo` writes only in `setTorMode` and `clearData`.
- **"Reads collide with writes" (§3, §6).** The comments cited are stale: private-chat reads and writes are under `privateChatsLock` since `142439a`. `ChatRepo.kt:174` is `meshPeers`; the mesh messages are the plain list at 170, under the mutex at 177.
- **Caller census (§3).** `ChatRepo` calls private `sendGeohashMessage` / `sendMeshMessage` overloads that take a message type. The short public ones have test callers only.
- **Nostr bus (§6, bug 2).** The terminal UI does have a collector (`TuiViewModels` creates `MainViewModel`). The defect stands for a start with none.
- **Budgets (§8)** are characters, not bytes: 1,000,000 per chat, 5,000,000 across strangers' chats.
- **Missed.** `data:repo` is not the only place a repository is implemented: `LocalConnectivityRepository`, and `AndroidConnectivityRepository` beside it, live in `data:local:platform`.

## Headline

1. **`Observe*` is fine as a name. The drift is that the rule is not applied.** 22 use cases return a `Flow`: 14 are `Observe*`, 8 are `Get*`. Two pairs in the tree already show the intended split (`GetPermissionState` / `ObservePermissionState`, `GetLocationServicesEnabled` / `ObserveLocationServicesEnabled`). Adopt *Observe = Flow, Get = one value* and rename the eight (§1).
2. **The domain module is a runtime, not a pure core.** It owns dispatchers (`expect val defaultContextFacade`, with platform source sets), application coroutine scopes named after transports, a global mutable logging policy read from an environment variable, an iOS lifecycle bridge, a stateful notices store, and a Koin module that wires concrete singletons for the whole app. On Apple the dispatcher facade maps `io`, `default`, `main` and `unconfined` **all to the main queue**, so every `withContext(io)` in the repositories runs on the iOS main thread. That is a platform bug living in `domain` (§5).
3. **`ChatRepository` is not a domain contract; it is the BLE transport's delegate exposed as one.** 62 methods, of which 20 have no caller outside `data/`. It carries channel-key derivation, encrypt/decrypt, inbound transport callbacks (`onSessionEstablished` is also a member of `BluetoothMeshDelegate`), a preferences read returning `Pair<Set<String>, Set<String>>`, session state as `Map<String, String>`, and LoRa radio control with the protocol passed as a `String` and re-parsed on the other side. `R/repositories/ChatRepo.kt` (3,333 lines, 27 constructor parameters) implements both interfaces and holds nearly every private-chat rule as a private function (§3, §8).
4. **Observation is three incompatible mechanisms, and the "domain" event bus is mostly the data layer's.** 21 hand-written copies of *subscribe to bus, re-read repository*; 50 emit sites in `data` against 17 in domain; five buses are `SharedFlow(replay = 1)` and one is a rendezvous `Channel` that can suspend a settings toggle forever; viewmodels subscribe to buses directly (§6).
5. **There is no error model.** `Outcome`/`Failure` is used by two use cases; three return `Boolean`; two `catch (e: Exception)` sites swallow cancellation; `LocationUnavailableException` is thrown in `data`, passed through `domain`, and caught in a viewmodel; the `Failure` subtypes are REST vocabulary used only by the HTTP client (§7).
6. **Plugins are only half pluggable.** LoRa protocols and Tor can be left out of an app. BLE and Nostr cannot: `ChatRepo` requires concrete `BluetoothMeshService`, `NostrTransport`, `NostrClient`, `NostrRelay`; twelve `data:repo` files import `com.bitchat.nostr.*`; `data:local:platform` depends on the Nostr plugin because one preferences interface lives in the wrong module. There is no transport interface in `data:remote:transport` (§10).
7. **Four bugs were found on the way that need no refactor:** the iOS main-queue dispatcher; selecting MeshCore on desktop or Android throws at runtime because those apps never register `meshcoreLoraModule` while the settings card offers it; the iOS Koin module list registers `commonLocal` and `localModule` twice; `LocationRepo` relay calls skip the Tor readiness gate that `ChatRepo` applies (whether traffic leaks depends on client-level enforcement and must be verified) (§12).

What is right and should be kept: `domain` has zero project dependencies and imports nothing outside `kotlin*`, `kotlinx*`, `koin-core` and itself. Presentation never calls a repository. No app instantiates a data class directly. Viewmodels are injected with use cases and almost nothing else. The feature-package layout, `data:repo` as the single adapter, the per-transport Koin modules and the `apps/<pair>` shared-parent pattern are all as designed.

---

## 1. The `Observe` question

`Observe<Model>` is a standard name for a use case that returns a stream and it follows the owner's *verb + model* rule. The first `Observe*` landed on 2026-10-04 (`ea7dd6b`) and most of them on 2026-10-05 (`085617a`, "Assemble the mesh channel's people in one domain use case"). What is off is consistency, not the word:

| Returns `Flow`, named `Get*` (8) | Returns `Flow`, named `Observe*` (14) | Returns one value, named `Get*` (21) |
|---|---|---|
| `GetAppTheme`, `GetBackgroundMode`, `GetJoinedChannels`, `GetJoinedNamedChannels`, `GetPowSettings`, `GetTorMode`, `GetTorStatus`, `GetUserNickname` | chat ×7, location ×6, tor ×1 (full list in the inventory) | the rest |

Presentation already treats the eight both ways: `VM/settings/SettingsViewModel.kt:90,186,231` calls `getPowSettings().firstOrNull()` as if it were one-shot while `VM/main/MainViewModel.kt:126` collects it.

Two further points about the stream use cases:

- `Usecase.invoke` is `suspend` (`D/base/Usecase.kt:4`), but no `Observe*` suspends before returning its `Flow`. Callers must enter a coroutine just to obtain a stream. `ObserveMeshChannelPeople` already dodges this with a non-suspending `invoke()` and therefore does not implement `Usecase` at all (`D/chat/ObserveMeshChannelPeople.kt:55-60`). Two more classes skip the interface: `GetPowSettings` and `SetPowSettings`.
- The body of a stream use case is the same five lines everywhere: `channelFlow { bus.events().onStart { send(repo.read()) }.collect { e -> if (matches(e)) send(repo.read()) } }`. It appears in 17 classes plus four inline copies inside `ObserveChannelMessages` (lines 37-84). The copies differ in ways nobody chose: `GetAppTheme` and `GetPowSettings` have no event filter and re-read on every event; `ObserveNotes` takes a geohash but does not filter events by it; only `ObserveMeshChannelPeople` de-duplicates snapshots. A single helper in `D/base` replaces all 21 (sketch in the plan).

Recommended rule: **`Observe<Model>` returns `Flow<T>` and does not suspend; `Get<Model>` returns one value and may suspend.** Renames: `GetAppTheme→ObserveAppTheme`, `GetBackgroundMode→ObserveBackgroundMode`, `GetJoinedChannels→ObserveJoinedChannels`, `GetJoinedNamedChannels→ObserveJoinedNamedChannels`, `GetPowSettings→ObservePowSettings` (plus a real one-shot `GetPowSettings` for the three `firstOrNull()` call sites), `GetTorStatus→ObserveTorStatus`, `GetUserNickname→ObserveUserNickname`, and `GetTorMode` is dead and can be deleted.

## 2. Domain use cases

82 classes are registered as use cases in `D/di/DomainModule.kt`. Per-class table: [[2026-10-12-domain-usecase-inventory]]. Totals: 22 return `Flow`, 2 return `Outcome`, 3 return `Boolean` as a failure signal, 40 contain no logic (29 pure pass-throughs, 8 pass-through plus one bus publish, 3 `when` dispatchers).

**Navigation lives in the domain.** `ActiveState.Settings / Locations / LocationNotes` are screens (`D/app/model/AppState.kt:21-25`). `SaveUserStateAction` (`D/user/SaveUserStateAction.kt`, 192 lines, 8 constructor dependencies) keeps a `previousChannel` back-stack (lines 176-182; the comment at 56 reads "can only come from Mesh channel I think"), duplicates the permission/Bluetooth/location gating already in `GetUserState.kt:20-25` (lines 184-191), registers the user as a geohash participant on relays (65-83), applies the mesh-DM naming rule (170-171), resolves and persists Nostr peers (116-145), publishes on three buses (157-160) and `println`s (155). `ResolveChatFallback` is the back-button rule (its KDoc names "Compose leave button and back"). `GetUserState` is the screen router. `VM/main/OrderedTransitions.kt:12-18` exists because `SaveUserStateAction` is not atomic, which is the use case's problem, not the viewmodel's.

**Presentation vocabulary inside domain.** `"anon"` at `D/user/GetUserNickname.kt:38` and `SaveUserStateAction.kt:67-68` (also in `R/repositories/ChatRepo.kt:1565,2735`, `R/repositories/UserRepo.kt:137`, `R/di/LoRaAppInitializer.kt:109-112`); `"large trout"` at `D/chat/ProcessChatCommand.kt:28`; comments that name the header, the settings switch, `MainViewModel` and `presentation:viewvo/CommandSuggestions.kt` (`D/tor/GetTorStatus.kt:24-31`, `D/tor/ObserveRequestedTorMode.kt:8`, `D/location/ObserveHasNotes.kt:52`, `ProcessChatCommand.kt:12-15`).

**Infrastructure inside use cases.** `EnableTor.kt:40-43` launches the native start fire-and-forget on `applicationScope`; `InitializeApplication.kt:16` picks a dispatcher; `ObservePeerSessionStates.kt:12-17` is a one-second `delay` loop over a `Map<String, String>`; `ObserveChannelMessages.kt:30` formats a Meshtastic node number as an 8-digit hex peer id; `SwitchLoRaProtocol.kt:15` passes `param.name` as a `String`; `ObserveMeshChannelPeople.kt:99-105` catches `RuntimeException` and `println`s to paper over a repository data race.

**Duplication.** "Current chat channel from `UserState`" is extracted five times (`ObserveChannelParticipants.kt:109-116`, `ObserveCurrentChannelBookmarkState.kt:39-42`, `ObserveHasNotes.kt:39-42`, `ResolveChatFallback.kt:26-29`, `SaveUserStateAction.kt:173-174`). `Channel.Mesh` as the default is hard-coded in `GetUserState.kt:12` and again at `SaveUserStateAction.kt:46,50,56`. `SetLoRaRegion` and `SetLoRaTxPower` are the same twenty lines; the test that covers them is called `ReconfigureLoRaSettingsTest` and describes one use case.

**Dead and mis-filed.** Zero callers outside `DomainModule`: `GetTorMode`, `IsUserBlocked`, `SendPrivateMessage`. Seven test files do not match a class; six are scenario-suffixed or test helpers, one is stale: `domain/src/jvmTest/.../location/ObserveGeohashPeopleTest.kt` contains `class ObserveChannelParticipantsTest`. Parameter wrappers come in three styles (`Params` ×10, `Request` ×3, `ChatCommandRequest`) plus raw primitives ×14. `initialization/models` is the only plural `models` package.

**Small correctness notes.** `SetChannelPassword.kt:93-96` calls `verifyPasswordOwnership` and discards the result. `JoinChannel.kt:47` publishes `ChannelChanged` on the already-joined path but not on the join or create paths. `JoinChannel.kt:117` and `SetChannelPassword.kt:53,102` use `catch (e: Exception)`, which turns a cancelled coroutine into `Outcome.Error`. `D/di/DomainModule.kt:109` registers `CoroutineScopeFacade` as `factory`, so every injection builds its own `SupervisorJob` and there is no single application scope; `data/repo/src/desktopTest/.../TorAppInitializerAppleDelegationTest.kt:85` has to override it with `single` to make cancellation work.

## 3. Domain contracts

### ChatRepository (`D/chat/repository/ChatRepository.kt`, 62 methods)

Caller census over `domain/`, `data/`, `presentation/`, `apps/`, `iosdi/` (tests and the `override` lines excluded):

| Callers | Count | Methods |
|---|---|---|
| none anywhere | 14 | `addChannelMember`, `addChannelMessage` (empty body in `ChatRepo`), `cleanupDisconnectedMembers` (empty), `clearAllChannels`, `decryptChannelMessage`, `encryptChannelMessage`, `getActiveLoRaProtocol`, `getChannelCreatorNpub`, `hasChannelKey`, `isChannelCreator`, `loadChannelData`, `observeJoinedNamedChannels` (re-implemented by `GetJoinedNamedChannels`), `onPeersUpdated`, `removeChannelMember` (empty) |
| only `ChatRepo` on itself | 5 | `addNamedChannelMessage`, `sendDeliveryAck`, `sendGeohashMessage`, `sendMeshMessage`, `sendReadReceipt` |
| only the BLE transport via the delegate | 1 | `onSessionEstablished` |
| domain use cases | 42 | the rest |

So 20 of 62 methods are on the domain contract without a domain consumer. They are there so that one class can implement two interfaces: `ChatRepo : ChatRepository, BluetoothMeshDelegate` (`R/repositories/ChatRepo.kt:158`; `onSessionEstablished` and `decryptChannelMessage` are members of both, see `data/remote/transport/bluetooth/.../BluetoothMeshService.kt:1734,1737`).

Of the 42 that domain does call, several are implementation-shaped: `deriveChannelKey` / `calculateKeyCommitment` are called from `JoinChannel.kt:79-80` and `SetChannelPassword.kt:82-83` to re-implement `verifyPasswordOwnership`, which is on the same contract; `storePersonDataForDM` / `getFullPubkey` / `getSourceGeohash` / `getDisplayName` are the domain shovelling cache entries into the repository from `SaveUserStateAction.kt:123-145`; `switchLoRaProtocol(protocol: String)` (KDoc still says "BITCHAT or MESHTASTIC", the enum has three values) is parsed back at `ChatRepo.kt:428-432` with `else -> BITCHAT`; `reconfigureLoRa` is radio control on a chat repository; `getPeerSessionStates(): Map<String, String>`; `loadChannelData(): Pair<Set<String>, Set<String>>`; `getMeshPeers()` returns `GeoPerson`, a location model. Lines 104-118 use fully-qualified `com.bitchat.domain.chat.model.ChannelMember` inline instead of imports.

A cohesive split is proposed in the plan (conversation store, message sender, private-chat state, peer presence, named channels, peer-identity directory, LoRa radio controller; crypto and transport callbacks leave the domain).

### The other contracts

- `LocationRepository` (47 lines) carries five responsibilities: Nostr notes, geohash channels and names, bookmarks, participant sampling and registration, OS location services and permissions. `sendGeohashMessage` has zero callers. `isLocationServicesEnabled` duplicates `ConnectivityRepository.isLocationServicesEnabled`. `registerSelfAsParticipant` is a relay action called only from `SaveUserStateAction.kt:70`.
- `UserRepository` mixes navigation state, profile, favourites (with a compare-and-set lambda, a storage-transaction shape), and the peer-id ↔ Nostr ↔ Noise directory. `getAppUserProfile` / `updateAppUserProfile` have no domain caller, so `D/user/model/Profile.kt` is dead.
- `AppRepository`: `hasRequiredPermissions`, `disableBatteryOptimization`, `enable/disableBackgroundMode` are platform actions, not reads.
- `TorRepository`: `getSocksProxyAddress(): Pair<String, Int>?` and `isProxyReady()` are HTTP-engine plumbing; mode exists three times (`getTorMode`, `getStoredTorMode`, `RequestedTorIntent.current`) and the KDoc at lines 18-31 explains why, which is the tell.
- `LoRaSettingsRepository` is the only fully synchronous repository.
- `BlockListRepository` is the one port with its own payload `Flow` (`observeBlockList()`) and no bus. It is the shape the rest should have.

## 4. Domain models

| Model | Where it is | Problem | Suggested home |
|---|---|---|---|
| `Channel` (sealed interface) | `D/location/model/ChannelID.kt:7` | File name ≠ class; it is the chat conversation id (`D/chat/ConversationKey.kt`) with `Meshtastic` and `NamedChannel` variants; imported from `location` by chat, app, user and `ChatNotices` | `D/chat/model/Channel.kt` |
| `UserState`, `ActiveState`, `AppState` | `D/app/model/AppState.kt` | Navigation state; `AppState` has zero references | `D/user/model/` or a `navigation` package; delete `AppState` |
| `GeoPerson` as a mesh peer | `ChatRepository.getMeshPeers()`, `ChatRepo.kt:170` | Mesh peers are not geo people; `D/chat/model/Peer.kt` (mutable `var`s, hand-written `equals`) has zero references | immutable `MeshPeer`; keep `GeoPerson` for geohash participants |
| `BluetoothConnectionEvent`, `LocationConnectionEvent` | `D/connectivity/model/BluetoothEvent.kt`, `LocationEvent.kt` | File ≠ class; the second clashes by file name with `D/location/model/LocationEvent.kt`; together with `ConnectionEvent` and `ConnectionState` they are four identical CONNECTED/DISCONNECTED enums | one state enum + a service discriminator |
| `GeoPoint` | inside `GeohashChannel.kt:14-15` | second type in file, `@Serializable` for nothing | own file |
| `ChannelTransport` vs `MeshChannelTransport` | `ChannelMember.kt:11-15`, `MeshChannelPerson.kt:6` | MESH/NOSTR/BOTH vs MESH/LORA | one transport vocabulary |
| `LoRaRegion`, `LoRaTxPower`, `LoRaBandwidth`, `LoRaProtocolType`, `LoRaSettings` | one file `D/lora/model/LoRaSettings.kt` | five types per file; a second `LoRaProtocolType` exists in the transport module (`data/remote/transport/lora/.../LoRaProtocolType.kt:6`) | split; one enum |
| `formatTitle`, `generateGeohashDisplayName`, `GeohashChannel.displayName`, `Note.displayName` | `D/location/model/ChannelDisplayName.kt:40`, `GeohashChannel.kt:11`, `Note.kt:10-19` | UI string formatting (`"#$geohashPart/@$displayNamePart"`, `"•"`, `"anon#xxxx"`) in model files | presentation mappers in `viewvo` |
| `LocationUnavailableException` | `D/location/model/` | exception in a model package, user-facing strings in its enum (lines 22-37) | see §7 |

Data-layer concerns on models: `BitchatMessage.encryptedContent` / `isEncrypted` have zero references outside the file; `TorStatus` carries `socksPort = 9050`, `routeGeneration`, `lastLogLine`, `bootstrapPercent` (presentation reads only `lastLogLine`); `ChannelInfo.nostrEventId` / `creatorNpub` are wire ids; timestamps are `Instant`, raw `Long` and `Int` seconds with no rule; peer ids, geohashes, Noise keys and npubs are all `String` (`MeshChannelPerson.kt:43-49` documents a `"lora-"` prefix "minted here and parsed nowhere"). The kotlinx.serialization plugin is applied to `domain` so that `data/local` can encode two records (`BlockedUser`, `FavoriteRelationship`); the `@Serializable` annotations on every `Channel` variant are unused because `UserState` is persisted by hand-written keys (`data/local/platform/.../LocalUserPreferences.kt:123-180`).

## 5. Runtime and infrastructure living in `domain`

| Item | What it is | Why it does not belong | Keep in domain |
|---|---|---|---|
| `CoroutinesContextFacade` + `expect val defaultContextFacade` (`D/base/CoroutinesContextFacade.kt:13`, actuals in `appleMain`, `jvmMain`, `linuxMain`) | the domain picks dispatchers for every layer | **verified:** `domain/src/appleMain/.../CoroutinesContextFacade.apple.kt:11-14` sets `io`, `main`, `default`, `unconfined` all to `NsQueueDispatcher(dispatch_get_main_queue())`; nothing in `iosdi` overrides it; `withContext(contextFacade.io)` at `ChatRepo.kt:384,421,465,496,1957` and in `UserRepo` therefore runs on the iOS main thread | nothing; `Dispatchers.IO/Default` are multiplatform now; at most an `interface DispatcherProvider` for tests |
| `CoroutineScopeFacade` / `DefaultScopeFacade` (`D/base/CoroutineScopeFacade.kt`) | application scopes named `bluetoothScope` (zero users), `nostrScope`, `connectivityEventScope`; registered as `factory`; exception handler `println`s | transport names on a domain type; no single app scope | nothing; apps own scopes, data modules receive a `CoroutineScope` |
| `LogPolicy` + `logBody/logBytes/logPath/logSafe` (`D/base/LogRedaction.kt`) | global `var messageBodies` configured from `BITCHAT_LOG_BODIES` by `apps/desktop/compose/.../App.kt:30` and `apps/embedded/.../Main.kt:133`; 30 files in `data/remote` depend on it; domain itself `println`s elsewhere | cross-cutting logging policy | nothing; a `core/logging` module below both domain and data |
| `AppForegroundState` (`D/app/AppForegroundState.kt`) | published from `data/local/platform/src/iosMain/.../IosAppLifecycleObserver.kt:14`, listened to by `R/tor/TorLifecycleCoordinator.kt:35`; unsynchronised `mutableListOf` of listeners; KDoc talks about `UIApplication` | an iOS lifecycle bridge the domain never reads | `interface AppLifecycle { val foreground: StateFlow<Boolean> }` at most |
| `ChatNotices` (`D/chat/ChatNotices.kt`, 148 lines) | bounded per-conversation system lines with an epoch-fenced reset | the logic is domain; the concrete singleton is injected straight into `ChatViewModel`, `MainViewModel` and even `presentation/viewvo/.../ChatState.kt`, and its `Epoch` is a `CoroutineContext` element callers must thread through | the rules, behind `ObserveNotices` / `AddNotice` use cases |
| `DomainModule` (`D/di/DomainModule.kt`, 255 lines) | registers concrete `InMemory*` buses, both facades, `AppForegroundState`, `ChatNotices`, and `InitializeApplication(getKoin().getAll<AppInitializer>())` | the domain's DI module is the app's runtime wiring | use-case factories only |
| `ConnectionEventBus` (`D/connectivity/eventbus/`) | implemented six times inside the BLE transport module; the Android one emits as a side effect of its getter (`AndroidConnectionEventBus.kt:132-136`); accessors are `suspend fun getXEvent(): Flow` | a port whose shape was set by its first implementation | a port with a plain `Flow` |

Ports implemented outside the domain that sit at feature roots with no folder convention: `RequestedTorIntent` / `MutableRequestedTorIntent` (implemented in `data/local/platform/.../LocalRequestedTorIntent.kt:20`; the KDoc at lines 6-25 is a design memo that references `TorRepo.getTorMode`), `TorCapability` (`R/tor/PlatformTorCapability.kt:9`), `TorRouteLifecycle` (`data/remote/rest/client/.../RouteAwareClientProvider.kt:86`, optional via `getOrNull()` at `DomainModule.kt:241,243`), `AppInitializer` (eight implementations). `AppForegroundState` is the inverse: a concrete domain class that data modules use as if it were a port.

## 6. The observation mechanism

Four bus shapes in one module: `MutableSharedFlow(replay = 1)` for app, chat, location, tor, user (exposed as `SharedFlow` by `AppEventBus.getAppEvent()` but as `Flow` by `ChatEventBus.events()`); a rendezvous `Channel<NostrEvent>()` exposed as `ReceiveChannel` (**verified:** `D/nostr/eventbus/InMemoryNostrEventBus.kt:12-17`, single consumer, `update()` suspends until someone receives, so `SetPowSettings` from the settings screen can hang when nothing is collecting); `suspend fun getXEvent(): Flow` for connectivity; `StateFlow` on `RequestedTorIntent`.

Events are flags ("go read the repository"), with payload exceptions (`GeohashMessagesUpdated(geohash)`, `NamedChannelMessagesUpdated(channelName)`, `FavoriteStatusChanged(peerID)`) that show the authors felt the need. Every subscriber re-reads a whole data set per event; `ObserveMeshChannelPeople.kt:64-81` and `ObserveChannelParticipants.kt:46-48` keep their own change-classification enums because the event cannot say what changed.

Who publishes: **50 emit sites in `data`** (`ChatRepo.kt` 42, `TorRepo.kt` 5, `LocationRepo.kt` 2, `R/initialization/HeadlessUserStateInitializer.kt` 1) against 17 in domain use cases. The data layer decides derived business facts (`UnreadPrivatePeersUpdated`, `LatestUnreadPrivatePeerChanged`) and some emissions are fire-and-forget (`nostrScope.launch { chatEventBus.update(...) }` at `ChatRepo.kt:682,714,1061,1310,1318,2159,2724`), so event order and state order are not the same. `ChannelJoined` / `ChannelListUpdated` are emitted by domain on the join path (`JoinChannel.kt:104-105`, `LeaveChannel.kt:19`) and by data on the create path (`ChatRepo.kt:3071-3072`). The contract KDoc on `getPrivateChatNames` (`ChatRepository.kt:26-31`) admits reads can collide with writes and tells callers to re-read on the event.

Mechanics: `replay = 1` plus `onStart { send(read()) }` means every new subscriber double-reads whenever the replayed event passes its filter (`GetTorStatus.kt:41-52`, `GetUserNickname.kt:18-32`, mesh branch of `ObserveChannelMessages`); `VM/chat/ChatViewModel.kt:103-104` does `.onStart { emit(ChannelChanged) }` and then also receives the replayed `ChannelChanged`, so the channel-switch handler runs twice on subscribe. `ObserveMeshChannelPeople.kt:61-69` works around the replay with `withIndex()`. With `extraBufferCapacity = 0` and `BufferOverflow.SUSPEND`, an emitter suspends until the slowest subscriber has consumed the previous event, and subscribers do suspending repository reads inside `collect`; `JoinChannel.kt:104-105` emits twice back to back. `update()` hops to `contextFacade.io` in the chat bus and to `default` in the other four for no reason, and on Apple `io` is the main queue.

Meanwhile the repositories already expose `Flow`s where someone needed one: `ChatRepository.observeLoRaPeerChanges(): Flow<Unit>` (a flag flow, the bus in another channel), `observeMiningStatus()`, `BlockListRepository.observeBlockList()`, `RequestedTorIntent.updates`, and `ChatRepo` holds `MutableStateFlow`s for geohash and mesh messages at `ChatRepo.kt:167,174` that could back `observe*` methods directly.

## 7. Error model

`Outcome` / `Failure` (`D/base/model/`) are produced by two use cases (`JoinChannel`, `SetChannelPassword`, with `ChannelFailure`) and consumed in `VM/chat/ChatViewModel.kt:474-486,540-555`. The generic `Failure.NetworkError / ServerError(code, body) / AuthenticationError / RateLimitError / UnknownError` are used only by `data/remote/rest/client/.../BaseApiClient.kt:141-175` (which maps a `TokenExpired` that this app does not have to `AuthenticationError`). `Outcome.isSuccess()` is called once, in `R/di/NostrAppInitializer.kt:95`. `D/chat/model/failure/ChatFailure.kt` is dead and mis-typed (`SendMessageFailure : Failure.FeatureFailure` is not a `ChatFailure`). `Outcome.Error(message, …)` carries prose the viewmodel ignores and re-maps anyway (`JoinChannel.kt:74`).

Everything else: `Boolean` (`ChatRepository.joinChannel / switchLoRaProtocol / reconfigureLoRa`, `SwitchLoRaProtocol`, `SetLoRaRegion`, `SetLoRaTxPower`; `JoinChannel.kt:92-97` turns the Boolean into `CreationFailed("Join operation failed")`); exceptions (`createNamedChannel` throws; `LocationUnavailableException` is thrown in `data/local/platform` on four platforms, passes `LocationRepo.kt:173` and `GetLocationGeohash`, and is caught at `VM/location/LocationChannelsViewModel.kt:158`); `null` (`getLastFixInfo`, `encrypt/decryptChannelMessage`); swallow and `println` (`ObserveMeshChannelPeople.kt:98-105`). `SaveUserStateAction.kt:120` uses `error(...)`. `EnableTor` launches `enable()` so its exceptions reach only the facade's `println` handler.

## 8. Where the business rules actually live: `data:repo`

`R/repositories/ChatRepo.kt` is 3,333 lines, 43% of the size of the whole domain module. Its constructor takes 27 parameters: concrete `BluetoothMeshService`, `NostrTransport`, `NostrClient`, `NostrRelay`, `NostrParticipantTracker` (required), `TorManager?`, `LoRaProtocol?` (an interface, downcast to `LoRaProtocolManager` at lines 422, 440, 461), seven preference and cache objects, four domain event buses, two other domain repositories (`UserRepository`, `AppRepository`, used only for the mesh-run rule at 270-271), three domain ports and three policy objects. It sets `mesh.delegate = this` at 253.

Rules implemented only as private functions there, in order of how much they are business:

1. Stranger-chat budget: chats the user never wrote in share 5,000 messages / 5 MB, the largest loses its oldest, the selected chat survives, the just-added message is never evicted (`enforceStrangerMessageBudget`, 1363-1408).
2. Unread semantics: unread iff `markUnread && !currentlyViewing && timestamp > lastRead`, else a read receipt is owed (`addPrivateMessage`, 1289-1300; `markPrivateChatRead`, 518-544).
3. Delivery-status state machine: a `Failed` report never takes back a receipt; only `Sending/Sent/Failed → Delivered` (1420-1421, 2712-2714).
4. Mesh-chat naming invariant: a mesh chat keeps the name it was opened under, a Nostr chat is named by its latest inbound sender (243-247, 412-419); the domain half-knows it (`SaveUserStateAction.kt:165-171` asks the repository).
5. Private routing decision tree and "only mutual favourites with a Nostr key are reachable over Nostr" (`sendPrivatePiece`, 1600-1700; `canSendViaNostr`, 2285-2312).
6. Outbox ordering: a text never goes out ahead of one written before it; stop at the first unsendable (159-161, 2208-2249).
7. LoRa sender disambiguation and cross-transport twin suppression (2434-2470 plus `R/utils/CrossTransportTwins.kt`).
8. Named-channel ownership: creator npub or verified password (2943-2956); `verifyPasswordOwnership` (2958-2970) duplicates `JoinChannel.kt:79-87`.
9. Favourite relationship transitions and the cap of 200 (`R/utils/FavoriteNotifications.kt:44-105`, driven at 1474-1510); the `[FAVORITED]:` prefix is known in three places including `D/chat/model/PrivateMessageText.kt:21` and a hand-built string at 1837.
10. When the mesh runs: `Active && permissions && Bluetooth` (init, 262-290), duplicated in `R/di/BluetoothMeshAppInitializer.kt:18-27`; `GetUserState` already holds the three repositories this rule needs.
11. Geohash participant activity window of five minutes (466-470) and proof-of-work acceptance (1037-1047).
12. Display order and timestamp trust: stored by arrival, shown by timestamp, future clamped (189-191, `R/utils/MessageLimits.kt:62`), the receive-side twin of `D/chat/model/SendTimes.kt`.

`R/utils/` is a hidden domain of pure, already unit-tested policy: `MessageLimits.kt` (per-chat 1,337 messages / 1 MB, stranger 5,000 / 5 MB, 4,096 receipt ids; its KDoc names `ChatRepo.enforceStrangerMessageBudget`), `CrossTransportTwins.kt` (131 lines, a 215-line pure test), `LearnedNames.kt` (bounded traffic-learned names; user-remembered names win), `MeshChatNames.kt` (the inverse of `D/user/Nicknames.kt`'s `meshChatName`; the "nameless peer shown as `take(12)`" rule is also at `ChatRepo.kt:400,1174,2680`), `FavoriteNotifications.kt`. `R/location/BookmarkNameResolver.kt:24-30,71-84` holds a pure geohash-length-to-level fallback table and a composite-name rule next to the geocoder loop. `R/lora/LoRaConfiguration.kt` maps region to frequency, sync word and spreading factor, values the domain `LoRaRegion` could carry. Dead: `R/utils/FileUtils.kt` plus four platform actuals, and `R/nostr/NostrRecipientResolver.kt` (which also duplicates `UserRepo.kt:104-118` and `ChatRepo.kt:1779-1807`).

Startup policy is scattered through `R/di/*Initializer.kt`: `HeadlessUserStateInitializer` decides the headless default state (`Active(Chat(Mesh))`, 37-38), emits `LoginChanged` purely to trigger `ChatRepo`'s hidden mesh starter (44, its own KDoc at 20-23 admits the coupling), and defines a Koin module at 53-55 outside `di/`; `LoRaAppInitializer` ties LoRa identity to `bluetoothMeshService.myPeerID` (108) and falls back to `"anon"`; the fresh-install Tor default is decided in `data/local/platform/.../LocalModule.kt:66`; the saved LoRa protocol is not reconciled at startup by anything in `data:repo` (worth checking each app passes `initialProtocol`). `R/di/SecureStoreStart.kt` answers "can the app start?" as a Koin extension.

Tor policy: the routing gate `torGateAllowsTraffic` (`R/tor/TorReadyGate.kt:117-134`) is a free function, so enforcement depends on each call site remembering it. **Verified:** only `ChatRepo` calls it (550, 640, 2854); `LocationRepo.getNotes` (71), `sendNote` (141) and `beginGeohashSampling` (250) reach relays without it. `TorRelayLogSink` and `TorRelayProxyStatus` depend on the concrete `TorRepo` and a non-contract method `recordExternalLogLine` (202). `TorRepo` holds the write side `MutableRequestedTorIntent` (32; writes at 199, 211) although the port's own KDoc says only user actions may write it.

Other items noticed: hard-coded `wss://relay.damus.io` at `ChatRepo.kt:3245`; UI copy `"[Encrypted message - wrong password?]"` in data at 3148-3166; `delay(2000)` waits at 338 and 656; the image/audio send block is duplicated at 806-862 and 1613-1662. The fifteen `ChatRepo*Test.kt` files under `data/repo/src/desktopTest` exist because the rules above can only be tested through the repository with transport fakes.

## 9. Presentation

Viewmodels are injected with use cases and almost nothing else, which is as designed. The exceptions:

| Where | What | Should be |
|---|---|---|
| `VM/main/MainViewModel.kt:72,116-128` | collects `UserEventBus` and re-runs `GetUserState` / refreshes favourites | `ObserveUserState`, `ObserveFavorites` |
| `VM/chat/ChatViewModel.kt:72,103-140` | collects `ChatEventBus`, re-reads `GetUserState` to find the active channel, and embeds "`BluetoothDisabled` → `Channel.Mesh`" at 129-132 | `ObserveActiveChannel` |
| `VM/chat/ChatViewModel.kt:90,149,187,196,439` | uses the `ChatNotices` store directly (`.notices`, `.resetting`, `.epoch()`, `.add`) | notices merged inside `ObserveChannelMessages`; `AddNotice` |
| `VM/chat/ChatViewModel.kt:45,~735` | imports `com.bitchat.mediautils.resolveMediaToLocalPath`; this is the only reason `presentation:viewmodel` depends on `data:mediautils` | inside `SendMessage` or a `PrepareOutgoingMedia` port |
| `presentation/tui/binding/.../TuiViewModels.kt:34-36` | resolves `BeginGeohashSampling` / `EndGeohashSampling` straight from Koin, bypassing `LocationChannelsViewModel` which owns them | through the viewmodel |

Business rules written in viewmodels (and then rewritten for the TUI): `ProcessChatCommand` only parses, so `ChatViewModel.handle{Block,Unblock,Join,Leave,Pass,List,Who,Message}Command` (302-420, 470-700) executes, including nickname-to-key resolution with the `"$target#"` disambiguation (317-320, 655-658), the `Channel.NostrDM(peerID = "nostr_${fullPubkey.take(16)}")` identity encoding (666; repeated at `MainViewModel.kt:220,556`) and failure-to-text mapping. `VM/chat/HugHelpers.kt` is dead (only its test calls it) and `ChatViewModel.kt:245-263` re-implements hug and slap with different output. `MainViewModel.goBack` (304-381) and `leaveDm` (393-407) decide where back goes per channel type, half of which is `ResolveChatFallback`. `DmViewModel.refusal` (114-121) decides which channels can carry DMs. `SettingsViewModel.onTorNetworkToggled` (192-227) and `loRaFailureMessage` (328-337) restate `TorAvailability` and `LoRaProtocol.supportsRadioConfiguration`. `LocationChannelsViewModel.kt:282-284` decides teleport state and 459-467 maps geohash length to `GeohashChannelLevel`; neither has a domain equivalent. `presentation/tui/.../Binding.kt:128-142` and `tui/binding/.../BitchatTui.kt:351-376` re-derive which channels use Nostr, the Tor-blocked notice and DM capability.

`presentation:design` depends on `data:mediautils` for `rememberAudioPlayer` and `rememberVoiceRecorder` (`design/.../core/VoiceNotePlayer.kt:25`, `design/.../chat/media/VoiceRecordButton.*.kt`). Those are Compose helpers; the module is mislabelled rather than the dependency being wrong. Screens and design import domain models directly (`Channel` ×9, `BitchatMessage` ×7, `GeoPerson` ×6, `PermissionState` ×5, …); `docs/architecture-summary.md` already calls this a pragmatic tradeoff.

## 10. Module layering and plugin swap-ability

`domain` is genuinely pure: no `project(...)` dependencies, no imports outside `kotlin*`, `kotlinx*`, `org.koin` and `com.bitchat.domain` (the Darwin imports are in its own `appleMain`, see §5). Presentation and apps never import a repository. No app instantiates a data class directly.

Edges that cut against the intended direction:

| Edge | Cause | Verdict |
|---|---|---|
| `presentation:viewmodel → data:mediautils` | `ChatViewModel.kt:45` | real; one import |
| `presentation:design → data:mediautils` | voice composables | mislabelled module; move them to a `presentation:design:media` sibling of `imagepicker` |
| `data:local:platform → data:remote:transport:nostr` | `local/prefs/impl/LocalNostrPreferences.kt:3` implements `com.bitchat.nostr.NostrPreferences`, whose interface lives in the Nostr plugin while every other `*Preferences` interface lives in `local:platform`; bound at `LocalModule.kt:35,61` | real inversion; local storage transitively drags in rest client, Tor, Noise and crypto; about four files to fix |
| `data:local:platform → data:remote:transport` | `SecureTransportIdentityProvider` implements `TransportIdentityProvider`; documented at `TransportIdentityProvider.kt:25-27` | deliberate shared leaf; the port would be cleaner in domain |
| `bluetooth → data:local:platform` | `bluetoothModule.kt:6-8` (`SecureIdentityPreferences`, hex helpers), `BluetoothMeshService.kt:45,1121` catches `SecureStoreUnavailableException`, desktop and Linux connection services use `com.bitchat.local.bridge.NativeLocationBridge` | real; `TransportIdentityProvider` exists and is bound (`LocalModule.kt:96`) and Nostr uses it (`NostrModule.kt:38`), Bluetooth bypasses it |
| `bluetooth → data:remote:rest:dto` | `IdentityAnnouncement`, `toBitchatFilePacket`, `toWireFormat` | not a break: `rest:dto` is misnamed, it holds the BLE and Nostr wire codecs |
| `rest:client → tor` | `ktorHttpClient.kt:9`, `RouteAwareClientProvider.kt:9`, `ClientModule.kt:9` | infra coupling; HTTP cannot exist without the Tor module compiled in |

There is **no common transport interface**: `data/remote/transport` contains only `TransportIdentityProvider.kt` and `MeshRadioLink.kt`. BLE is a 1.7k-line concrete `BluetoothMeshService`; Nostr is four concrete classes; LoRa alone has an interface. Swap-ability per transport:

| Transport | Rating | Evidence |
|---|---|---|
| BLE | entangled | required concrete dependency and `BluetoothMeshDelegate` implementation in `ChatRepo`; `R/repositories/AppRepo.kt:3`, `R/di/SecureStoreStart.kt:3`, `R/di/LoRaAppInitializer.kt:108` |
| Nostr | entangled | twelve `data:repo` files import `com.bitchat.nostr.*`, including generic utilities hosted inside the plugin (`GeohashUtils`, `Bech32`, hex helpers); required by `ChatRepo` and `LocationRepo`; `data:local:platform` depends on it |
| LoRa as a whole | easy | `getOrNull()` plus the `LoRaProtocol` interface; iOS ships without it (`iosdi/.../Koin.kt:25-38`) |
| one LoRa protocol | ~10 files | `LoRaProtocolManager.kt:27-29,55-60` hard-codes three lazies; two parallel `LoRaProtocolType` enums; `.name` crosses the domain boundary as a `String`; three app-level `LoRaProtocolSelector` objects (`apps/desktop/.../di/`, `apps/droid/.../di/`, `apps/embedded/src/linuxArm64Main/.../di/`) each re-read the preference with their own backend and hard-code the `"lora_settings"` / `"lora_protocol"` keys from `data/local/platform/.../LocalLoRaPreferences.kt:84-90`; desktop's and Android's do not know `MESHCORE` |
| Tor | ~3 files | `TorRepo(torManager = get())` is unconditional (`R/di/CommonRepoModule.kt:157`); `rest:client` and `nostr` depend on `data:remote:tor` at build level; no app omits `torModule`, so it is untested |

## 11. Composition roots

The module list is written out five times with no shared list: `apps/desktop/.../di/DesktopModules.kt:25-41` (shared by the two desktop apps, the only reuse), `apps/droid/.../BitchatApplication.kt:52-70`, `apps/embedded/compose/.../Main.kt:92-110`, `apps/embedded/tui/.../TuiApplication.kt:34-50` (comment: "same modules as the Compose app, in the same order"), `iosdi/.../di/Koin.kt:25-38`. Drift already visible: **verified** iOS lists `commonLocal` and `localModule` twice; Android orders `commonRepoModule` before `domainModule` while the others do not; `repoModule` is both `includes(repoModule)` inside `commonRepoModule` (`CommonRepoModule.kt:38`) and listed again on desktop and embedded; `headlessUserStateModule` is attached by list concatenation on desktop (`DesktopTuiBuildIdentity.kt:23`) and by `includes()` on embedded (`BuildConfigModule.kt:25`); `meshcoreLoraModule` is registered only by the two embedded apps. `iosdi/.../KoinIos.kt:31-33` registers every Swift-supplied initializer with `single { initializer }` and no qualifier, so later definitions override earlier ones; worth checking. `apps/droid/.../BitchatApplication.kt:35,40` calls a `data:mediautils` static initialiser before Koin and `runBlocking { initializeApp() }` on the main thread. `apps/desktop/.../net/DesktopNetworkModule.kt:18-25` encodes "desktop enforces Tor, Android does not" as wiring rather than a capability.

## 12. Bugs found on the way (fix regardless of any refactor)

1. **iOS dispatchers.** `domain/src/appleMain/.../CoroutinesContextFacade.apple.kt:11-14`: all four contexts are the main queue. Every repository `withContext(io)` and every bus `update()` hop runs on the main thread on iOS.
2. **Nostr bus can hang a settings toggle.** `D/nostr/eventbus/InMemoryNostrEventBus.kt`: rendezvous `Channel`; `SetPowSettings` → `update()` → `send` suspends until a receiver takes the event; the only receiver is the `GetPowSettings` flow collected by `MainViewModel`; two collectors would split events.
3. **MeshCore selection crashes on desktop and Android.** `presentation/design/.../settings/LoRaSettingsCard.kt:139` offers `LoRaProtocolType.entries`; `apps/desktop/.../DesktopModules.kt:38-39` and `apps/droid/.../BitchatApplication.kt:66-67` register only `bitChatLoraModule` and `meshtasticLoraModule`; `LoRaProtocolManager.active` resolves the MeshCore definition from Koin and throws `NoDefinitionFoundException`.
4. **iOS double module registration.** `iosdi/.../di/Koin.kt:25-38`.
5. **`LocationRepo` relay calls are not behind the Tor readiness gate** (`R/repositories/LocationRepo.kt:71,141,250` vs `ChatRepo.kt:550,640,2854`). Whether this leaks traffic depends on whether the Ktor engine itself refuses direct routes when Tor is requested; verify on desktop (where `TorEnforcingEngineProvider` is bound) and on Android (where it is not).
6. `catch (e: Exception)` swallowing `CancellationException` at `JoinChannel.kt:117`, `SetChannelPassword.kt:53,102`.
7. `CoroutineScopeFacade` registered as `factory` (`DomainModule.kt:109`), so nothing owns one application scope.
8. `ChatViewModel.kt:103-104` runs the channel-switch handler twice on subscribe because of `onStart { emit }` plus `replay = 1`.
9. `SetChannelPassword.kt:93-96` discards the result of `verifyPasswordOwnership`.
10. `iosdi/.../KoinIos.kt:31-33` initializer override (to verify).

## 13. Dead code

Domain: `GetTorMode`, `IsUserBlocked`, `SendPrivateMessage` (use cases); `ChatRepository.observeJoinedNamedChannels`, `LocationRepository.sendGeohashMessage`, `UserRepository.getAppUserProfile` / `updateAppUserProfile` and therefore `D/user/model/Profile.kt`; `D/chat/model/Peer.kt`; `AppState`; `D/chat/model/failure/ChatFailure.kt`; `BitchatMessage.encryptedContent` / `isEncrypted`; the `@Serializable` annotations on `Channel` and `GeoPoint`; `DefaultScopeFacade.bluetoothScope`; the 14 uncalled `ChatRepository` methods in §3; `originalDisplayName` parameter in `ChannelDisplayName.kt`. Data: `R/utils/FileUtils.kt` and its four actuals; `R/nostr/NostrRecipientResolver.kt`; `ChatRepo.getActiveLoRaProtocol`. Presentation: `VM/chat/HugHelpers.kt`. Build: the commented `:logging:logger` line in `presentation/viewmodel/build.gradle.kts:42`. Tests: the file name `ObserveGeohashPeopleTest.kt`.

## Appendix: numbers at `389fe0a`

| Layer | Kotlin lines |
|---|---|
| domain | 7,823 (207 files) |
| data | 108,642 |
| presentation | 34,841 |
| apps | 5,810 |
| iosdi | 202 |

`ChatRepo.kt` 3,333; `ChatViewModel.kt` 799; `MainViewModel.kt` 668; `LocationRepo.kt` 492; `DomainModule.kt` 255. Repository contracts: Chat 121 lines, Location 47, User 46, Tor 39, App 25, BlockList 21, LoRaSettings 18, Nostr 10, Connectivity 6.

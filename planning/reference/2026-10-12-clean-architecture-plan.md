---
title: Clean architecture plan — decisions, phases, guardrails
date: 2026-10-12
commit: 389fe0a
tags: [bitchatkmp, architecture, plan, domain, clean-architecture, guardrails]
status: proposal, partly decided 2026-10-12
related:
  - "[[2026-10-12-clean-architecture-review]]"
  - "[[2026-10-12-domain-usecase-inventory]]"
---

# Clean architecture plan — decisions, phases, guardrails

- **Date:** 2026-10-12, against `389fe0a`.
- **Input:** the findings in [[2026-10-12-clean-architecture-review]] (section numbers below refer to it as "review §n") and the per-class table in [[2026-10-12-domain-usecase-inventory]].
- **Scope:** a plan the owner can run incrementally on a live, four-platform app with limited review bandwidth. Nothing here has been implemented. Sizes: S = hours, M = a day or two, L = a week or more.
- **Why the drift happened, in one line:** nothing executable said no. The only workflow is `.github/workflows/desktop-release.yml` and it runs no tests; there is no Konsist, detekt or ktlint; there is no tracked `CLAUDE.md` or `AGENTS.md` (the owner keeps local, gitignored ones); the rules exist only as prose in `docs/architecture-summary.md`, which has since left the repository. The guardrails in §3 are therefore not the last step but the first.

## Status after the owner's review (2026-10-12)

The owner has decided parts of §1; `planning/DECISIONS.md` is the record and wins over this document. In short:

- **(a) Naming:** `Observe<Thing>` returns a Flow, `Get<Thing>` returns one value, both on the one `Usecase` interface. `FlowUsecase` is **not** adopted; viewmodels may `launch` to collect.
- **(b) Observation:** the owner keeps the event buses in the domain and the `GetJoinedChannels` pattern. The `refreshOn` helper and the move to repository Flows (§1b option 3, §2 Phase 1.4 and Phase 4) are **not adopted**; Phase 4 shrinks to fixing concrete bus problems.
- **(c) Error model:** agreed in principle: `Outcome` with a sealed failure per feature. Not implemented yet.
- **(d), (e), (f):** open.
- **Phase 0:** done on `claude/naming-rule-and-safety-fixes` except 0.5, 0.6, 0.7 and 0.9, which turned out to be latent, not a leak, no visible effect, and not a bug (see the corrections in the review). 0.3 hides MeshCore where its module is not registered instead of registering it.
- **Phase 1.3 (renames):** done on the same branch.

## Status after the local review (2026-10-12)

From local-claude and Codex; evidence in [[2026-10-12-local-review]]. Read these with the phases they name.

- **The first slice is on `main`** without 0.1. As pushed it did not compile above `:domain` (a viewmodel function shadowed a renamed use case) and failed the desktop Koin `verify()`; both are fixed.
- **0.1, iOS dispatchers, is not done.** It is on `claude/apple-io-off-main` with `io` limited to one worker, as 0.1 itself proposed, and waits for the owner's iPhone. `default` cannot follow yet: `IosAppLifecycleObserver.initialize` requires the main thread and runs on `default`.
- **0.3** went further than hiding MeshCore: Settings hide LoRa where no protocol is registered, and a saved protocol the app cannot run reads as BitChat.
- **Decision (e)** recommends deleting the dispatcher facade because the project "does not use" it for tests. It does: the `data:repo` tests put the test dispatcher into every context and scope. Whatever replaces the facade stays injectable.
- **1.1, §3.2, §3.6** put the rules in `docs/architecture.md` and a root `CLAUDE.md`. `docs/` has left the repository and `CLAUDE.md` is gitignored on purpose. Where tracked rules live is the owner's call; until then they are in `planning/CONVENTIONS.md`.
- **1.8 and 1.9** are not one mechanical change. Split them. `ChannelTransport` (MESH, NOSTR, BOTH) and `MeshChannelTransport` (a set of MESH, LORA) mean different things. `:apps:common` contradicts the owner's rule that `apps/` holds only apps.
- **2.3, notices.** The epoch is captured before a command starts and the send is refused while a wipe runs. Capturing it when the notice is posted is too late. Move command execution and notices together, or neither.
- **3.3, `AppLifecycle`.** A `StateFlow` alone conflates; `TorLifecycleCoordinator` needs every background edge. Keep ordered edges and the first synchronous value.
- **4.3.** The two emit sites are two paths, not a duplicate.
- **5, `ChatRepo`.** "Must not change" is too short. The lock domains, single writers, bounds and "no derived copy of repository state" in `planning/CONVENTIONS.md` all hold; a budget decision made on a snapshot must not be applied to state that changed since. Start with narrow interfaces bound to the one `ChatRepo` and move no state.
- **5.1.** `deriveChannelKey` / `calculateKeyCommitment` cannot simply give way to `verifyPasswordOwnership`: that one refuses a channel with no commitment, which `JoinChannel` accepts, and it records ownership as a side effect.
- **§3.** The list still requires `FlowUsecase` (rejected by D1), forbids viewmodel parameters that exist for good reason, and its layering rule fails `data:repo`, which composes the transports by design. Rewrite it before building a check from it.
- **"Ship as separate PRs"** gives way to D16: one branch per slice, squash-merged.
- **Each of 2.3, 3.1, 3.3, 5 and the Apple dispatchers needs a short written design reviewed locally before code.** The cloud session builds only `:domain`, so slices that stay inside it suit it best.

## 0. What drifted, on one screen

| Area | Intended | Actual |
|---|---|---|
| Domain | pure contracts and use cases | the chat domain lives in `data/repo/.../ChatRepo.kt` (3,333 lines, 27 constructor parameters, also the `BluetoothMeshDelegate`); the domain module hosts the runtime instead: dispatchers (the Apple actual maps every context to the main queue), scopes (registered as `factory`), `LogPolicy`, `AppForegroundState`, six in-memory buses, `ChatNotices`, the serialization plugin |
| Use cases | `Verb+Model : Usecase` | 82 registered; 3 implement no interface; 22 return `Flow` of which 8 are named `Get*`; 21 copies of the bus-then-re-read template; 3 dead; 3 parameter-wrapper styles; 7 test files whose name matches no class |
| Observation | explicit feature-scoped events | three mechanisms (bus and re-read, repository Flows, a one-second poll); four bus shapes including a rendezvous channel; data emits 50 events, domain 17 |
| Errors | one rule | `Outcome` in 2 use cases, `Boolean` in 3, exceptions elsewhere, three `catch (e: Exception)` sites that swallow cancellation |
| Presentation | viewmodels consume use cases only | two viewmodels inject buses, one injects the `ChatNotices` store and a `data:mediautils` function; command execution, DM capability, back navigation and nickname rules live in viewmodels and are re-derived for the TUI |
| Plugins | swappable transports | no transport port; BLE and Nostr are required concretes in `ChatRepo`; `data:local:platform` depends on the Nostr plugin; three copies of `LoRaProtocolSelector`; five hand-written composition roots with visible drift |

## 1. Decisions to make first

Each has two or three options, the tradeoff, and a recommendation. Everything in §2 assumes the recommended option unless noted.

### (a) `Observe` / `Get` naming and a `FlowUsecase` interface

- **Option 1, recommended.** Rule: `Observe<Thing>` returns `Flow<Thing>` and implements a new non-suspending `FlowUsecase<P, T> { operator fun invoke(param: P): Flow<T> }`; `Get<Thing>` is `suspend` and returns `Thing` once; everything else is verb + noun on `Usecase`. Rename the eight `Get*` that return a Flow.
- Option 2: one interface, just rename. Cheaper by one interface, but a `suspend invoke` that never suspends before returning a cold Flow forces a `launch {}` in every viewmodel just to obtain the stream, and the rule cannot be type-checked.
- Option 3: drop `Observe` and let the return type speak. Reads worse at call sites and throws away the two `Get` / `Observe` pairs that already exist.
- Why 1: none of the 22 Flow use cases suspends before returning (review §1); `ObserveMeshChannelPeople` and `ObserveRequestedTorMode` are already effectively non-suspending. A second interface makes the rule a one-line Konsist check: implements `FlowUsecase` ⇔ name starts with `Observe`.

### (b) Buses versus repository Flows

- Option 1: repositories expose `observeX(): Flow<T>` carrying the data; use cases `combine` and `map`; buses remain only for cross-feature notifications that are not data (logout, clear-all).
- Option 2: keep the buses but fix the shape once (`MutableSharedFlow(replay = 0, extraBufferCapacity = 64, DROP_OLDEST)`, `events(): Flow<E>` everywhere, no `withContext` in `update`, payload events) and route every subscriber through one `refreshOn` helper.
- **Option 3, recommended.** Option 2 now (an afternoon; removes the double read on subscribe and the back-pressure wedge), then Option 1 feature by feature behind `refreshOn`, so each migration swaps the source Flow inside one use case without touching callers. The end state is Option 1. `ChatRepo` already holds `MutableStateFlow`s at lines 167 and 174 that can back `observe*`; a StateFlow updated in the same critical section as the data is ordered by construction, which the fire-and-forget `nostrScope.launch { bus.update(…) }` sites are not.

### (c) Error model

- **Option 1, recommended.** Commands that can fail return `Outcome<T, F>` where `F` is a sealed failure declared per feature (`ChannelFailure` already exists). Queries return a value or `null`. No exception crosses a use-case boundary except `CancellationException`. `catch (e: Exception)` is forbidden in domain. `Boolean` is never a failure signal. Delete `Failure.NetworkError / ServerError / AuthenticationError / RateLimitError` from domain (only `BaseApiClient.kt:141-175` uses them; move them there) and the dead `ChatFailure`. `Outcome.Error` carries no UI prose.
- Option 2: domain-defined exceptions, `runCatching` in viewmodels. Idiomatic, but `LocationUnavailableException` already shows the result: a domain type thrown in four platform services, passing through a repository and a use case, caught in a viewmodel, with user strings inside.
- Option 3: a sealed result per use case and no shared `Outcome`. Most precise, most boilerplate, and agents invent a new shape each time.
- Why 1: it is what presentation already consumes (`ChatViewModel.kt:474-486,540-555`), and the per-feature `F` gives Option 3's precision where it matters.

### (d) 1:1 pass-through use cases

- **Option 1, recommended.** Keep them as the convention and delete the three dead ones. 40 of 82 are pass-throughs of about ten lines each; the payoff is one mechanically checkable sentence: *a viewmodel constructor accepts only `Usecase` and `FlowUsecase` types*. Collapsing them reopens repository access from viewmodels, which is exactly the door agents walk through.
- Option 2: collapse them and let viewmodels inject repositories for trivial reads. Fewer files, no enforceable boundary.
- Option 3: keep them but only write one when a viewmodel needs it. That is Option 1 plus one sentence in the rules document; adopt the sentence.

### (e) Where coroutine scopes and dispatchers live

- **Option 1, recommended.** Domain has neither. Use cases are `suspend` and never `launch`. Data modules receive one app-owned `CoroutineScope` from the app's runtime module and use `Dispatchers.IO` / `Dispatchers.Default` directly (both are multiplatform now). Delete `CoroutinesContextFacade`, `CoroutineScopeFacade` and the platform actuals. The one domain `launch` (`EnableTor.kt:40-43`) moves into `TorRepo` or `TorAppInitializer`.
- Option 2: keep the facades in domain, fix the Apple actual and the `factory` registration. This is the Phase 0 hotfix, not the destination.
- Option 3: move the facades to a `:core:concurrency` module below domain and data. Preserves a testability indirection the project does not use (`runTest` and `Dispatchers.setMain` suffice).

### (f) A `MessageTransport` port now, or BLE + Nostr as a fixed core

- Option 1: define the port now and make `ChatRepo` fan out over `List<MessageTransport>`. The port would be shaped by `ChatRepo`'s current private helpers.
- Option 2: accept BLE + Nostr as the core, keep LoRa and Tor pluggable, fix the three concrete inversions.
- **Option 3, recommended.** Option 2 now; revisit the port after Phase 5 splits `ChatRepository`, because the resulting `MessageSender` / `PeerPresence` interfaces *are* the transport-facing surface and the port becomes a rename rather than a design. Be honest that BLE is the product's identity (the peer id is the BLE peer id; LoRa identity is `bluetoothMeshService.myPeerID`); swapping it is not a realistic requirement today.

## 2. Phases

Ordered by value ÷ risk with dependencies respected. Presentation-onto-use-cases is pulled forward to Phase 2 because it is small and de-risks the bus-shape change in Phase 4. The three cheapest plugin fixes are pulled into Phase 1 because they are mechanical and remove live duplication.

### Phase 0: safety fixes, no refactor (S each, about two days in total, ship as separate PRs)

| # | Fix | Files | Verify |
|---|---|---|---|
| 0.1 | **iOS dispatchers.** Map `io → Dispatchers.IO`, `default → Dispatchers.Default`, `main → Dispatchers.Main`, `unconfined → Dispatchers.Unconfined`. Risk: iOS has been serialising all repository work on the main queue, which may have hidden races that already exist on Android and desktop (the `ChatRepository.kt:26-31` KDoc admits reads collide with writes). Mitigation: first step `io = Dispatchers.IO.limitedParallelism(1)` (off main, still serial), widen after Phase 5. | `domain/src/appleMain/.../CoroutinesContextFacade.apple.kt` | iOS simulator: mesh chat, geohash notes, settings; watch for Kotlin/Native concurrent-access exceptions; confirm `ChatRepo.kt:384,421,465,496,1957` run off main |
| 0.2 | **Nostr bus.** Replace the rendezvous `Channel` with the `SharedFlow` shape of the other buses; change `NostrEventBus.getNostrEvent(): ReceiveChannel` to `events(): Flow<NostrEvent>`. Today it works only because `MainViewModel.kt:126` keeps a live collector; in the TUI, headless, or in tests `SetPowSettings` suspends forever after persisting, and a `Channel` fans out so a second observer silently misses events. | `D/nostr/eventbus/InMemoryNostrEventBus.kt`, `NostrEventBus.kt`, `GetPowSettings.kt` | Turbine test: `update` with zero collectors returns; two collectors both see the event |
| 0.3 | **MeshCore crash.** The module already builds for jvm, android and ios. Add `meshcoreLoraModule` to `apps/desktop/.../di/DesktopModules.kt` and `apps/droid/.../BitchatApplication.kt:66-69`; add a `MESHCORE` branch to the desktop and droid `LoRaProtocolSelector` (deleted again in 1.9). | 4 files | desktop and Android: Settings → LoRa → MeshCore, no `NoDefinitionFoundException` |
| 0.4 | **iOS double modules.** Delete the second `commonLocal`, `localModule` at `iosdi/.../di/Koin.kt:33-34`. | 1 file | existing Apple binding regression test |
| 0.5 | **iOS initializers.** `iosdi/.../KoinIos.kt:31-33` registers each Swift `AppInitializer` as an unqualified `single`; if more than one exists only the last runs. Verify, then register with `named("ios-$i")` or as a list. | 1 file | count initializers run at launch |
| 0.6 | **Tor gate for `LocationRepo`.** `getNotes` (71), `sendNote` (141), `beginGeohashSampling` (250-255) reach relays without `torGateAllowsTraffic` (`R/tor/TorReadyGate.kt:117`). First check whether the websocket client used by `NostrRelay.kt:52` obtains its engine through `RouteAwareClientProvider`, which throws when Tor is requested but not ready; if so the gap is limited to the no-proxy engines (iOS, linuxArm64) where the gate is the only refusal. Either way add the gate at the three sites mirroring `ChatRepo.kt:2314`. | `LocationRepo.kt` | data:repo test: fake Tor "requested, not ready" → relay fakes record no calls |
| 0.7 | `DomainModule.kt:109` `factory<CoroutineScopeFacade>` → `single`. | 1 line | `TorAppInitializerAppleDelegationTest.kt:85` already overrides with `single`; remove the override |
| 0.8 | Rethrow `CancellationException` at `JoinChannel.kt:117`, `SetChannelPassword.kt:53,102`, `ObserveMeshChannelPeople.kt:99-105`. | 3 files | `runTest` cancelling the scope mid-call completes |
| 0.9 | `SetChannelPassword.kt:93-96` discards the result of `verifyPasswordOwnership`: use it or delete the call. | 1 file | a `JoinChannelTest`-style test |
| 0.10 | **Dead code with zero callers:** `GetTorMode`, `IsUserBlocked`, `SendPrivateMessage` and their `DomainModule` lines; `ChatRepository.observeJoinedNamedChannels` and `ChatRepo.kt:3023`; `R/utils/FileUtils.kt` and four actuals; `R/nostr/NostrRecipientResolver.kt`; `D/chat/model/Peer.kt`; `Profile` and `UserRepository.getAppUserProfile / updateAppUserProfile`; `ChatFailure`; `VM/chat/HugHelpers.kt` (re-point `HugCommandTest` at the real hug/slap rule when it moves in 2.4). Do **not** delete `BitchatMessage.encryptedContent / isEncrypted` until confirmed absent from `toWireFormat` and `BitchatFilePacketMapper`. | ~12 files | compiles; `:domain:jvmTest` |

Must not change: wire codecs (`data/remote/rest/dto`, `bluetooth/.../serialization.kt`), persisted keys, the user-visible Tor-indicator semantics documented in `GetTorStatus`.

### Phase 1: conventions and mechanical cleanups (M in total, many S PRs; do 1.1 first)

1. **Write the rules.** `docs/architecture.md` (§3.2) and a root `CLAUDE.md` pointer, before any rename, so the renames follow a written rule.
2. **`FlowUsecase`** in `D/base/Usecase.kt` plus `operator fun <T> FlowUsecase<Unit, T>.invoke()`. Move all 22 Flow use cases onto it; make `GetPowSettings`, `SetPowSettings`, `ObserveMeshChannelPeople` implement an interface.
3. **The eight renames** (§4). Live callers outside domain: one to seven files each, about 25 in total; no Swift file references a domain type, so iOS needs no source change. IDE rename; tests renamed with them.
4. **`refreshOn`** in `D/base/util/`:
   ```kotlin
   fun <E, T> Flow<E>.refreshOn(
       matches: (E) -> Boolean = { true },
       read: suspend () -> T,
   ): Flow<T> = filter(matches)
       .map { }
       .onStart { emit(Unit) }
       .map { read() }
       .conflate()
       .distinctUntilChanged()
   ```
   Replace the 21 copies one feature per PR (chat; location; tor, app, user, nostr). `distinctUntilChanged` absorbs the `replay = 1` double read until Phase 4 sets `replay = 0`. Fix `GetAppTheme` and `GetPowSettings` (no event filter) and `ObserveNotes` (parameter not in the filter) while there. Multi-bus cases become `merge(busA.events().filter{…}, busB.events().filter{…}).refreshOn { … }`. Add `RefreshOnTest` with Turbine; the existing `GetJoinedChannelsTest`, `GetTorStatusModeTest`, `GetUserNicknameTest` and `Observe*Test` cover the rest.
5. **Parameter style:** nested `data class Params` (already 10 of 14); rename the three `Request` and `ChatCommandRequest`; a single primitive stays raw.
6. **Test file names:** seven renames in `domain/src/jvmTest` (`ObserveGeohashPeopleTest.kt → ObserveChannelParticipantsTest.kt`, `GetTorStatusModeTest → ObserveTorStatusTest`, `DisableTorRouteRecoveryTest → DisableTorTest`, `ReconfigureLoRaSettingsTest` split or justified by a `ReconfigureLoRa` use case replacing `SetLoRaRegion` + `SetLoRaTxPower`). Fully-qualified names in `ChatRepository.kt` and `ChatViewModel.kt:103-107` become imports.
7. **File ≠ class:** `location/model/ChannelID.kt → Channel.kt`; `connectivity/model/BluetoothEvent.kt → BluetoothConnectionEvent.kt`; `connectivity/model/LocationEvent.kt → LocationConnectionEvent.kt`; `initialization/models → initialization/model`, `Version` to its own file. `base/LogRedaction.kt` is handled in Phase 3.
8. **Model moves:** `Channel → chat/model` (about 69 import sites, IDE move; persisted `UserState` uses hand-written keys at `LocalUserPreferences.kt:123-180`, so no persisted format changes; assert that with a test that loads a fixture). `UserState / ActiveState → user/model`, delete `AppState`. `GeoPoint` out of `GeohashChannel.kt`. One `ConnectionState` enum instead of four. `ChannelTransport` and `MeshChannelTransport` unified. Add a `UserState.activeChannel` extension and delete the five copies. Centralise the `"anon"` fallback in `user/Nicknames.kt` (five copies today). The `"large trout"` text moves to presentation.
9. **Cheap plugin fixes, pulled forward.** (i) Move the `NostrPreferences` interface from `data/remote/transport/nostr` to `data/local/platform` (about four files including `NostrDeviceSeedCustodyTest` and `IdentityMintGateTest`). (ii) `loraProtocolManagerModule` reads `get<LoRaPreferences>().getLoRaProtocol()` itself (`LocalLoRaPreferences.kt:84-90` already parses it); delete the three `LoRaProtocolSelector` files and the `initialProtocol` parameter at the six call sites. (iii) One `bitchatCoreModules()` list in a small new `:apps:common` KMP module (consistent with the existing `:apps:desktop` and `:apps:embedded` parents), consumed by all five roots including `iosdi`; this also fixes Android's module order and the double `repoModule`.

Must not change: the `lora_settings` / `lora_protocol` preference keys; Koin last-wins module ordering semantics.

### Phase 2: presentation onto use cases, part one (M)

1. `ObserveUserState` (= `refreshOn(UserEvent.StateChanged | LoginChanged) { getUserState() }`) and `ObserveFavorites`; `MainViewModel.kt:106-128` drops `UserEventBus`.
2. `ObserveActiveChannel: Flow<Channel?>` embedding the `BluetoothDisabled → Channel.Mesh` rule from `ChatViewModel.kt:129-132`; `distinctUntilChanged` on the value is safe against `SaveUserStateAction`'s double announce (a stale first read then a correct one are two distinct values). `ChatViewModel` drops `ChatEventBus`.
3. **Notices.** `ObserveChannelMessages` merges `ChatNotices` internally; `PostChatNotice` / `ResetChatNotices` use cases replace `.add` / `.resetting`. The `Epoch` context element is the hard part; if it resists, keep it inside `ObserveChannelMessages` / `SendMessage` and expose nothing. `ChatViewModel`, `MainViewModel` and `viewvo/ChatState.kt` stop importing `ChatNotices`.
4. `ExecuteChatCommand` use case: nickname-to-key resolution with the `$target#` disambiguation (`ChatViewModel.kt:317-320,655-658`), the `nostr_${fullPubkey.take(16)}` encoding repeated at `MainViewModel.kt:220,556`, failure-to-notice mapping. `ProcessChatCommand` keeps parsing. Pure rules shared with the TUI become model functions: `Channel.usesNostr`, a `DmCapability` (`DmViewModel.kt:114-121` and `BitchatTui.kt:351-357`), geohash length → `GeohashChannelLevel` (`LocationChannelsViewModel.kt:459-467`).
5. `resolveMediaToLocalPath`: a `MediaResolver` port in `chat/port`, implemented in `data:mediautils` (which gains a `domain` dependency), used by `SendMessage`; remove `:data:mediautils` from `presentation/viewmodel/build.gradle.kts`. `presentation:design → mediautils` (the audio player and recorder composables) stays allow-listed until a `presentation:design:media` module is worth it.
6. `TuiViewModels.kt:34-36` goes through `LocationChannelsViewModel` instead of Koin-resolved use cases.

Tests: move viewmodel-level command tests to `ExecuteChatCommandTest`; `MainViewModel` and `ChatViewModel` tests use fake use cases only. Verify on desktop Compose, the TUI (`-Ptui.enabled=true`) and Android: channel switch, `/j`, `/block`, `/w`, DM from a geohash.

### Phase 3: domain purity (M to L)

1. **Scopes and dispatchers** (decision e). A `runtimeModule` in `:apps:common` registers `single<CoroutineScope>(named("app"))`; `ChatRepo`, `LocationRepo`, `NostrAppInitializer` take it; `contextFacade.io` becomes `Dispatchers.IO`; delete both facades, `DefaultScopeFacade` and the Apple actual. `InitializeApplication` runs initializers under `coroutineScope { launch }` on the caller's context; `EnableTor`'s launch moves to `TorRepo`. Android's `runBlocking { initializeApp() }` at `BitchatApplication.kt:40` is a separate behaviour change; leave it.
2. **Logging.** Domain does not log: delete the four `println` sites including `CoroutineScopeFacade.kt:22`. `LogPolicy` moves to a zero-dependency `:core:logging` used by data and apps only. The rule stays "domain has no project dependencies".
3. **`AppForegroundState`** → `interface AppLifecycle { val foreground: StateFlow<Boolean> }` in `app/port`; the implementation in `data/local/platform` (publisher `IosAppLifecycleObserver.kt:14`, consumer `TorLifecycleCoordinator.kt:35`).
4. **Ports folder.** `<feature>/repository/` is data access; `<feature>/port/` is everything else (`RequestedTorIntent`, `TorCapability`, `TorRouteLifecycle`, `AppInitializer`, `ConnectionEventBus`, `AppLifecycle`, a `ChatNoticeStore`). Nothing concrete at a feature root except use cases. The write side of `RequestedTorIntent` leaves `TorRepo.kt:32`.
5. **`domainModule`** = `factory { UseCase(…) }` only. The in-memory buses, `ChatNotices` and `InitializeApplication(getAll<AppInitializer>())` move to a `domainRuntimeModule` in the same package, documented as "apps include both".
6. **Drop the serialization plugin** from `domain/build.gradle.kts`: `BlockedUser` and `FavoriteRelationship` get surrogates in `data/local/platform` with identical field names; round-trip test against a JSON fixture captured from today's build.

Must not change: the persisted JSON of the block list and favourites; Tor fail-closed behaviour.

### Phase 4: observation migration (M to L, per feature)

1. **One bus shape** (decision b, step one): `replay = 0, extraBufferCapacity = 64, DROP_OLDEST`, `events(): Flow<E>`, non-suspending `tryEmit`. Safe only after Phase 2 (no viewmodel subscribes directly and `ObserveUserState` reads on start). Delete `ChatViewModel.kt:103-104`'s `onStart { emit(ChannelChanged) }`.
2. **Per feature,** repositories gain `observeX(): Flow<T>`; the use case swaps its `refreshOn` source for the repository Flow; `ChatEvent` variants that only ever meant "re-read X" are deleted as their last subscriber goes. Order: user (`observeUserState`, `observeFavorites`), then tor (`TorRepo` already has a status flow), then chat (`observeJoinedChannels`, `observePrivateChats`, `observeUnreadPeers`, `observePeerSessionStates` replacing the one-second poll), then location.
3. Events emitted by both domain and data (`ChannelJoined` / `ChannelListUpdated` at `JoinChannel.kt:104-105` and `ChatRepo.kt:3071-3072`) collapse to one source.

Verify: every `Observe*Test` passes unchanged (they assert values, not events); a desktop smoke test on each feature PR.

### Phase 5: `ChatRepository` split and pulling the rules up (L, two to three weeks, incremental)

Strangler approach so the fifteen `ChatRepo*Test.kt` files in `data/repo/src/desktopTest` stay green: add narrow interfaces in domain, have `ChatRepo` implement them (`single { ChatRepo(…) } bind …`), move use cases to the narrow interface one at a time, extract physically only when a slice no longer touches `ChatRepo` private state.

1. **Contract pruning (S, first).** Delete the 16 zero-caller methods; move `onSessionEstablished`, `addNamedChannelMessage`, `sendReadReceipt`, `sendDeliveryAck` to an internal interface on the delegate side; drop `deriveChannelKey` / `calculateKeyCommitment` (use cases call `verifyPasswordOwnership` / `setChannelPassword` only, deleting the duplicate at `JoinChannel.kt:79-87`).
2. **Pure rules up, in this order** (each: move the function and its test, `ChatRepo` imports from domain): `R/utils/CrossTransportTwins.kt → chat/model` (test exists); `R/utils/MessageLimits.kt` and `enforceStrangerMessageBudget` (1363-1408) as a pure function over a snapshot (`ChatRepoMessageLimitsTest`); `R/utils/LearnedNames.kt` and `R/utils/MeshChatNames.kt → user/Nicknames.kt`, unifying the `take(12)` / `take(16)` rule (400, 1174, 2680); delivery-status transitions (1420, 2712) → `DeliveryStatus.next()`; unread semantics (1289-1300, 518-544); `R/utils/FavoriteNotifications.kt` transitions and the cap of 200 → `user`, injecting `isValidNpub`; named-channel ownership (2943-2970); `R/location/BookmarkNameResolver.kt` pure parts → `location`; `canSendViaNostr` (2285-2312) → `PrivateRoute.choose(…)`; the mesh-run rule (262-290 and `R/di/BluetoothMeshAppInitializer.kt:17-28`) → a `ShouldRunMesh` use case; the Tor gate decision table → a `TorRoutingPolicy` pure function fed by `torGateAllowsTraffic`.
3. **Interfaces:** `ConversationStore` (reads and `observe*`), `MessageSender`, `PeerPresence` (a typed `MeshPeer`, observable; replaces the `Map<String, String>` session states), `NamedChannelRepository`, `PeerIdentityDirectory` (`user/repository`, merged with `UserRepository`'s Nostr mapping), `LoRaRadioController` (`lora/repository`, typed enum). Selected peer and channel fold into `UserState`.
4. **Physical extraction** of `ChatRepo` into those implementations: optional, after 1 to 3. `HeadlessUserStateInitializer` moves to `domain/initialization`.

Must not change: Noise, BLE, Nostr and LoRa packet formats; outbox ordering; the stranger-budget numbers; the favourite cap.

### Phase 6: remaining plugin boundaries (M; optional L)

1. `bluetooth → local:platform`: identity through the already-bound `TransportIdentityProvider` (`LocalModule.kt:96`, as Nostr does at `NostrModule.kt:38`); a `LocationPermissionGate` port for the four desktop and Linux files using `NativeLocationBridge`; `SecureStoreUnavailableException` handling moves behind the provider.
2. **LoRa typed end to end:** the domain `LoRaProtocolType` only; one adapter maps to `com.bitchat.lora.LoRaProtocolType`; `LoRaProtocolManager` takes `Map<LoRaProtocolType, Lazy<LoRaProtocol>>` and refuses unknown types with an `Outcome`, never an exception; the settings card lists `availableProtocols` from the manager. Fixes the stale KDoc at `ChatRepository.kt:40`.
3. Rename `data:remote:rest:dto → data:remote:protocol` (it holds the BLE and Nostr codecs); move `TorRouteSource` into `rest:client` so Tor depends on the client rather than the reverse.
4. A composition-root test per app: Koin `verify()` on `bitchatCoreModules()`, which catches the MeshCore class of bug at test time.
5. Optional `MessageTransport` port (decision f) once 5.3 exists.

## 3. Guardrails, in priority order

1. **A CI test job, first (about 40 lines of YAML).** `.github/workflows/check.yml` on `pull_request`: `./gradlew :domain:jvmTest checkLayering --console=plain`, extended to `:presentation:viewmodel:jvmTest` and `:data:repo:desktopTest` once an Android SDK step is in place. Without this every other guardrail is advisory.
2. **`docs/architecture.md` plus a root `CLAUDE.md` (about a page plus 15 lines).** Rules as checkable sentences; `CLAUDE.md` repeats the ten that matter and says "run `./gradlew :domain:jvmTest checkLayering` before finishing". Agents read root files; this is the cheapest lever. Suggested sentences:
   - `domain` has no project dependencies.
   - A class in the root of `com.bitchat.domain.<feature>` implements `Usecase` or `FlowUsecase`.
   - `Observe*` ⇔ `FlowUsecase` ⇔ returns `Flow`; `Get*` is `suspend` and returns once.
   - A command that can fail returns `Outcome<T, FeatureFailure>`, never `Boolean`.
   - Never `catch (e: Exception)` in domain.
   - No `launch`, `CoroutineScope(`, `Dispatchers`, `println` or `delay` in a use case.
   - `<feature>/model` imports only other models. `<feature>/repository` is data access; `<feature>/port` is everything else.
   - `domainModule` contains only `factory { UseCase }`.
   - Viewmodels inject only use cases. Presentation never imports `*.eventbus.*`, `*.repository.*`, `ChatNotices`, `com.bitchat.repo` or `com.bitchat.mediautils`.
   - `data:local:*` never imports a `data:remote:transport:*` plugin. Wire codecs live in `data:remote:protocol`.
   - Persisted keys never change without a migration test. One composition list in `:apps:common`.
3. **A `checkLayering` Gradle task (about 40 lines in the root `build.gradle.kts`, wired into `check`).** Walks `project(…)` dependencies and asserts: `:domain` has none; `:presentation:*` has no `:data:*` except an explicit allowlist (`:presentation:design → :data:mediautils` until it moves); `:data:local:*` has no `:data:remote:transport:*` leaf except `:data:remote:transport`; only `:apps:*` and `:iosdi` depend on more than one transport leaf. Independent of the Kotlin compiler version, so it works today on Kotlin 2.5.0-Beta1.
4. **Konsist tests (100 to 150 lines; `domain/src/jvmTest/.../ArchitectureTest.kt` and `presentation/viewmodel/src/jvmTest/.../ArchitectureTest.kt`).** Feature-root classes implement `Usecase` / `FlowUsecase` or are allow-listed (`ChatNotices` until Phase 3); `FlowUsecase` implementors start with `Observe` and `Usecase` implementors do not; `Get*` invoke types do not start with `Flow`; no `println`; no `launch` / `GlobalScope` / `CoroutineScope(` in use-case files, no `Dispatchers.` in domain after Phase 3; `*.model` files import no `*.repository` / `*.eventbus` / `*.port`; the nested parameter class is named `Params`; every `jvmTest` `<X>Test` maps to an existing `<X>`; viewmodel constructors accept only `Usecase` / `FlowUsecase` types; viewmodel sources import none of the forbidden packages. Risk: Konsist ships its own Kotlin PSI and the domain uses `-Xwhen-guards`; if it fails to parse, fall back to a 60-line plain Kotlin test that scans source files with regular expressions for the same rules.
5. **Koin `verify()` test per composition root (S).** Prevents the "settings offers what Koin cannot build" class of bug.
6. **A PR template (about 10 lines):** which rule in `docs/architecture.md` this touches; new use case named Observe, Get or verb; any new failure type or `Boolean`; any new `project(…)` dependency and why; any persisted key or wire format touched.
7. **detekt, optional, later:** `ForbiddenImport`, `ForbiddenMethodCall(println)`, `TooGenericExceptionCaught`. Overlaps Konsist and may lag Kotlin 2.5; skip until the beta settles.

Do 1, 2 and 3 in the same half day; 4 and 5 the next day; 6 with the first Phase 1 PR.

## 4. The `Observe` question, plainly

`Observe*` is fine and it is the right word: it says "this keeps emitting" where `Get*` says "this answers once". What went wrong is that nobody wrote the rule down, so later agents wrote `Get*` use cases that return a `Flow` and `Observe*` ones that implement no interface. Adopt: `Observe<Thing>` returns `Flow<Thing>` and implements a non-suspending `FlowUsecase`; `Get<Thing>` is `suspend` and returns `Thing`; both may coexist for the same thing (two such pairs already do). The eight renames: `GetAppTheme → ObserveAppTheme`, `GetBackgroundMode → ObserveBackgroundMode`, `GetJoinedChannels → ObserveJoinedChannels`, `GetJoinedNamedChannels → ObserveJoinedNamedChannels`, `GetPowSettings → ObservePowSettings` (plus a real one-shot `GetPowSettings` for the three `firstOrNull()` call sites), `GetTorStatus → ObserveTorStatus`, `GetUserNickname → ObserveUserNickname`, and `GetTorMode`, which has zero callers, so delete it rather than rename it. While there, make `ObservePowSettings`, `SetPowSettings` and `ObserveMeshChannelPeople` implement the interfaces, and rename the mismatched test files so the rule is visible in the test tree too.

## 5. Files that anchor the work

- `domain/src/commonMain/kotlin/com/bitchat/domain/base/Usecase.kt` — add `FlowUsecase`; the anchor for the naming rule and the Konsist checks.
- `domain/src/commonMain/kotlin/com/bitchat/domain/di/DomainModule.kt` — scope `single`, dead use-case removal, later the `domainModule` / `domainRuntimeModule` split.
- `domain/src/appleMain/kotlin/com/bitchat/domain/base/CoroutinesContextFacade.apple.kt` — the Phase 0 iOS main-thread fix; deleted in Phase 3.
- `data/repo/src/commonMain/kotlin/com/bitchat/repo/repositories/ChatRepo.kt` — the Tor gate pattern at 2314, the rule sites for Phase 5, the StateFlows at 167 and 174 for Phase 4.
- `build.gradle.kts` — home of the `checkLayering` task, the only compiler-independent guardrail.

---
title: Local review of the first slice and of the planning documents
date: 2026-10-12
tags: [bitchatkmp, architecture, review, local]
related:
  - "[[2026-10-12-clean-architecture-review]]"
  - "[[2026-10-12-clean-architecture-plan]]"
---

# Local review of the first slice and of the planning documents

- **Who:** local-claude, with two read-only reviews by Codex (`gpt-6-astra`, high effort): one of `claude/naming-rule-and-safety-fixes` at `23dad85`, one of the documents on `planning` at `daee7b7`. local-claude checked each finding it took against the code, and built every target.
- **Base:** the owner's `main` had four commits the cloud session had not seen (`06178a4`, `132a910`, `d912bd6`, `3c46c5c`). The branch was merged onto them first.
- Paths: `D/` = `domain/src/commonMain/kotlin/com/bitchat/domain/`, `R/` = `data/repo/src/commonMain/kotlin/com/bitchat/repo/`, `VM/` = `presentation/viewmodel/src/commonMain/kotlin/com/bitchat/viewmodel/`.

## 1. The code branch

### What did not build or pass

| # | Found by | What | Done |
|---|---|---|---|
| 1 | build, Codex | `SettingsViewModel` had functions `observeTorStatus()` and `observeBackgroundMode()`; after the rename the injected use cases got the same names, so every call resolved to the function and returned `Unit`. `presentation:viewmodel` and everything above it did not compile | functions renamed `collectTorStatus`, `collectBackgroundMode` |
| 2 | build, Codex | `DesktopModulesTest.desktopGraphVerifies` failed: Koin `verify()` found no definition for the constructor parameter of `RegisteredLoRaProtocol` | `definition<RegisteredLoRaProtocol>(LoRaProtocolType::class)` added to the test's `injectedParameters` |
| 3 | build | `main`'s `LaterSubscriptionRepoTest` still overrode the deleted `bluetoothScope` | override removed |
| 4 | merge | `d912bd6` on `main` made the proof-of-work flow follow the miner (`repository.observeMiningStatus()`); the branch's `ObservePowSettings` did not have it | `ObservePowSettings` is `main`'s flow on `eventBus.events()`; `GetPowSettings` stays the one-shot read; `PowSettingsObservationTest` uses `ObservePowSettings` |
| 5 | merge | the branch edited `docs/architecture-summary.md`, which `main` deleted with all of `docs/` | deletion kept |

### `b2e6cd5`, the Apple dispatchers: taken out of this merge

It is on its own branch, `claude/apple-io-off-main` (one commit on `main`, on the owner's machine), and waits for the owner's iPhone test. Reasons, each read in the code:

- **`IosLocationService` is driven from wherever it is called.** `LocationRepo.getLocationGeohash` and `getAvailableGeohashChannels` call `getCurrentLocation()` inside `withContext(io)`. With `io` off the main queue the service sets the `CLLocationManager` delegate and starts and stops updates from a worker, while CoreLocation calls the delegate on the thread that made the manager; the plain `resumed` flag is then shared by two threads and a continuation can be resumed twice. Fixed on the new branch: the service does its work on the main queue itself.
- **State that only the single queue kept safe.** In `ChatRepo`: `channelNostrEventIds`, the named-channel message lists (`getOrPut` then add and trim, read by `getNamedChannelMessages`), `channelKeys`, `verifiedOwnerChannels`. In `LocalChannelPreferences`: `setJoinedChannel` and `removeJoinedChannel` read the whole set and write it back. In `NostrClient`: `geohashIdentityCache`. None has a lock. The same races exist today on Android, desktop and Linux, where `io` has always been a pool; iOS would newly get them.
- **The plan's own first step was skipped.** Plan 0.1 says to start with `io = Dispatchers.IO.limitedParallelism(1)`: off the main queue, still one at a time. The commit used the unrestricted pool. The new branch uses the limited one. That removes worker against worker; main queue against worker remains.
- **"Only `io` moved" is not what the commit does.** `unconfined` changed too (no production code uses it).
- **A smoke test cannot show there is no race.** The device test is necessary and not sufficient; the collections above need an owner each.

The unsynchronised collections are their own work, on every platform, and are not part of this slice.

### Smaller things

| What | Done |
|---|---|
| `loraAvailable = getLoRaSettings != null` on a parameter that is never null: iOS showed a LoRa card with an empty protocol list (Q3) | `loraAvailable = loraProtocols.isNotEmpty()`; viewmodel test |
| A saved MeshCore choice on desktop or Android showed as current with no matching option while the radio ran BitChat (Q4) | `LoRaSettingsRepo.getLoRaSettings()` reports a saved protocol the app cannot run as the first one it can, which is what the manager falls back to; the preference is not rewritten; `LoRaSettingsRepoTest` |
| Nothing pinned which protocols an app offers | `DesktopModulesTest.desktopOffersOnlyTheLoRaProtocolsItRegisters` builds the real desktop graph and expects BitChat and Meshtastic |
| Comments that narrated or restated | trimmed in the LoRa modules, `LoRaProtocolManager`, `RegisteredLoRaProtocol`, the terminal settings screen, `SettingsState`, `LoRaSettingsRepository` |
| Commit messages with long bodies, questions and two trailers each | gone with the squash; see D14 |

### Read and found fine

The six renames have no stragglers in any source set or in Swift. The Nostr bus has one publisher (`SetPowSettings`) and one collector (`ObservePowSettings`); a replayed event costs one more read and nothing acts on it twice. Every deleted symbol was checked for references, Koin lookups and serialization: none. `BitchatMessage` is not serialized, so dropping `encryptedContent` and `isEncrypted` changes no format. `JoinChannel` and `SetChannelPassword` rethrow cancellation and the new test fails without the change. Android and both desktop apps register BitChat and Meshtastic, both embedded apps all three, iOS none.

## 2. The planning documents

Corrections are also noted at the top of the review and the plan. B = a planned step rests on it.

| # | Where | Claim | What the code says | Consequence |
|---|---|---|---|---|
| 1 | Plan 0.1, Q1 | `default` can follow `io` after a device test | `InitializeApplication` launches every initializer on `default`, and `IosAppLifecycleObserver.initialize` starts with `check(NSThread.isMainThread)` | B. Moving `default` crashes at launch. See Q1 |
| 2 | Review, dispatcher correction | keeping `default` on the main queue keeps Bluetooth work serial | `IosSharedCentralManager` and `IosGattServerService` already get their callbacks on other queues, and chunked sends suspend between chunks | B. The condition for moving `default` is an owner for each service's state, not a device run |
| 3 | Plan 3.3 | `AppForegroundState` becomes `interface AppLifecycle { val foreground: StateFlow<Boolean> }` | `AppForegroundState.listen` exists because a `StateFlow` conflates; `TorLifecycleCoordinator` queues every edge | B. A short background would be lost and Tor not stopped. Keep ordered edges and the first synchronous value |
| 4 | Plan 5 | keeping packet formats, outbox order, budget numbers and the favourite cap is enough | three lock domains in `ChatRepo`; adding a message, unread state and stranger eviction happen in one locked step; eviction also removes names, keys and aliases | B. `CONVENTIONS.md`, last section. Start with narrow interfaces bound to the one `ChatRepo`; move no state |
| 5 | Plan 5.2 | `enforceStrangerMessageBudget` "as a pure function over a snapshot" | a decision made on a snapshot is applied to state that has changed since | B. Same |
| 6 | Plan 2.3 | notices behind `PostChatNotice` / `ResetChatNotices`, `Epoch` hidden | `ChatViewModel.sendMessage` refuses while a reset runs and captures the epoch before the command starts; `ChatNotices.add` drops a result from an older epoch | B. Capturing the epoch when the notice is posted is too late. Move command execution and notices together, or leave both |
| 7 | Plan 5.1 | drop `deriveChannelKey` and `calculateKeyCommitment`; use cases call `verifyPasswordOwnership` | `JoinChannel.joinExistingChannel` accepts a protected channel with no advertised commitment; `verifyPasswordOwnership` refuses one, and on a match records ownership, caches the key and publishes | B. Not equivalent. Define the operation first |
| 8 | Plan §3.3 | only apps and `iosdi` may depend on more than one transport | `data/repo/build.gradle.kts` depends on Bluetooth, Nostr and LoRa, and the plan keeps it so | B. The first gate would fail on the intended design. Allow `data:repo` |
| 9 | Plan (e), 3.1 | the dispatcher facade is testability "the project does not use" | `ChatRepoTestHelper`, `TorRepoTest`, `PowSettingsObservationTest` and `LaterSubscriptionRepoTest` put the test dispatcher into every context and scope | Recommendation (e) option 1 rests on this. Keep something injectable |
| 10 | Plan 1.1, §3.2, §3.6 | rules go in `docs/architecture.md` and a root `CLAUDE.md` | `docs/` is gone and gitignored on `main`; `CLAUDE.md` is gitignored on purpose | Where tracked rules live is the owner's call. Until then: `CONVENTIONS.md` here |
| 11 | Plan 1.9 (iii) | a new `:apps:common` module for the shared module list | the owner's rule: `apps/` holds only apps, shared code in a pair's parent module | Find another home before proposing it |
| 12 | Plan §3 | guardrails as written | §3.2 and §3.4 still require `FlowUsecase`, which D1 rejected; "viewmodel constructors accept only use cases" rejects `SettingsViewModel.showBackgroundModeSetting` and `PermissionsErrorViewModel`'s parameter | Rewrite the list before building a check from it |
| 13 | Plan 1.8, 1.9 | "mechanical cleanups" | 69 import sites, persisted-model moves, an enum merge (`ChannelTransport` is MESH/NOSTR/BOTH, `MeshChannelTransport` a set of MESH/LORA: different meanings), preference ownership, five composition roots | Split; specify the enum mapping before merging the enums |
| 14 | Plan 0, 1 | "ship as separate PRs" | D16: squash merges, few commits | One reviewable branch per slice |
| 15 | Review §6, Plan 4.3 | `ChannelJoined` / `ChannelListUpdated` are emitted twice | `JoinChannel` publishes them when joining an existing channel; `ChatRepo` when creating one. One operation never does both | Split ownership, not duplication. Removing either stops updates on its path |
| 16 | Review, bus correction | read on subscribe plus `replay = 1` closes the gap | the replay holds only the latest event and observers filter it; an unrelated later event hides a relevant one. Only `ObserveMeshChannelPeople` treats the first event as "everything changed" | True for that one observer. Test the others' start individually |
| 17 | Review §1, §6 | `GetAppTheme`, `GetPowSettings` and `ObserveNotes` lack a filter alike | `AppEvent` has two variants (a filter would help); `NostrEvent` has one; `LocationEvent.NotesChanged` carries no geohash | Only the first is a missed filter. `ObserveNotes` needs a payload first |
| 18 | Review, Tor correction | `LocationRepo` fails at once where `ChatRepo` waits 30 s | `RouteAwareClientProvider` waits for a ready route too, 30 s by default | Both fail closed when the route is acquired; `LocationRepo` only lacks the extra check up front |
| 19 | Review §8, Plan 3.4 | `TorRepo` holding `MutableRequestedTorIntent` breaks the port's rule | the port's KDoc allows a user action or an explicit reset; `TorRepo` writes in `setTorMode` and `clearData` only | Not a violation |
| 20 | Review §3, §10 | the comments on `getPrivateChatNames` and in `ObserveMeshChannelPeople` show reads colliding with writes | stale since `142439a`: reads and writes of private chats are under `privateChatsLock` | Audit collections one by one; do not cite those comments. `ChatRepo.kt:174` is `meshPeers`; mesh messages are the list at 170 under the mutex at 177 |
| 21 | Review §3 | `sendGeohashMessage` and `sendMeshMessage` are called by `ChatRepo` itself | it calls private overloads that take a message type; the short public ones have test callers only | Count by signature. See Q5 |
| 22 | Plan 0.2 | the terminal UI has no collector, so a settings change hangs | `TuiViewModels` creates `MainViewModel`, which collects | The defect was real (no receiver suspends, two split events); that scenario was not |
| 23 | Review §8 | budgets of 1 MB and 5 MB | `MessageLimits` counts `String.length` | characters |
| 24 | Review §10 (missed) | `data:repo` is the one place repositories are implemented | `LocalConnectivityRepository` in `data:local:platform` implements `ConnectivityRepository` and is bound in `LocalModule`; `AndroidConnectivityRepository` sits beside it | A divergence the review did not list |
| 25 | README, D12 | questions go in commit messages | D14 | Questions live in `STATUS.md` |

## 3. The five questions

Recommendations for the owner. Q3 and Q4 are implemented on the branch because they finish what `af2ca13` started; say so if either should come out.

- **Q1: no, not yet.** Two things first. `IosAppLifecycleObserver` has to get itself onto the main queue (row 1). And `IosGattClientService.connections`, `IosGattServerService.subscribedCentrals` and the scan flags in `IosCentralScanningService` each need one owner: they are already touched from CoreBluetooth's queues and from application-scope jobs. `io` itself should stay limited to one worker until the collections in §1 have locks.
- **Q2: yes for theme and nickname, no for background mode.** `GetAppTheme` (Settings reads it once) and `GetUserNickname` (`DmViewModel` and `LocationNotesViewModel` read it once per send), keeping the "anon" fallback of `ObserveUserNickname`. For background mode drop the single read instead: `SettingsViewModel` reads it once and then collects the same flow.
- **Q3: yes.** Done.
- **Q4: yes.** Done on the read side; start-up already falls back to BitChat (`LoRaProtocolSelector` on desktop and Android, `LoRaProtocolManager.setActiveType` everywhere), so display and radio agree.
- **Q5: remove them now, as a local job.** `ChatRepository.onPeersUpdated` (callers: `ChatRepoNoiseKeyChatTest` only), `sendGeohashMessage(content, geohash, nickname)` (`ChatRepoMessageLimitsTest`, `ChatRepoLearnedNamesTest`), `sendMeshMessage(content, nickname)` (`ChatRepoMessageLimitsTest`), and `BluetoothMeshDelegate.decryptChannelMessage` (no caller; two test overrides in the Bluetooth module). The tests move to `sendMessage(…, Channel.Location / Channel.Mesh, …)`. These are security tests and the cloud session cannot compile them, so a local agent does it and checks that each guard still fails when its rule is removed.

## 4. Before the next phase

- Work from `main` after this slice lands; it has the relay, proof-of-work and `docs/` commits.
- The cloud session builds only `:domain`. Give it domain-only slices, or expect a local build-and-fix pass on every branch, as here.
- Phase 3.1 (facades), 3.3 (`AppLifecycle`), 2.3 (notices), 5 (`ChatRepo`) and the Apple dispatchers each need a short written design reviewed locally before any code.
- D5, the error model, wants its own small slice: `Outcome` has one type parameter today and its `Error` carries the generic `Failure`. Decide the typed shape, then take one LoRa operation through end to end.
- Rewrite plan §3 against D1 to D16 before building a check from it.

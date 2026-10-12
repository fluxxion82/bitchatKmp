---
title: Domain use-case inventory
date: 2026-10-12
commit: 389fe0a
tags: [bitchatkmp, architecture, review, domain, usecases, inventory]
status: reference
related:
  - "[[2026-10-12-clean-architecture-review]]"
  - "[[2026-10-12-clean-architecture-plan]]"
---

# Domain use-case inventory

Every class registered in `domain/src/commonMain/kotlin/com/bitchat/domain/di/DomainModule.kt` as a use case at `389fe0a`. 82 classes; 79 implement `Usecase`, 3 do not (marked). Companion to [[2026-10-12-clean-architecture-review]]; the renames and the helper that collapses the `poll` rows are in [[2026-10-12-clean-architecture-plan]].

**Kind legend.** PT = 1:1 pass-through to one repository call · PT+bus = one repository call then one bus publish · disp = `when` dispatch to one of two repository methods · poll = hand-rolled *subscribe to bus, re-read repository* flow · orch = orchestration across repositories and buses · rules = real business rules · nav = navigation-state logic · launch = starts background work.

| feature | class | verb | param | result | deps | lines | kind | notes |
|---|---|---|---|---|---|---|---|---|
| app | ClearAllData | Clear | Unit | Unit | 9 | 45 | orch | 7 repos + `chat.ChatNotices` barrier + UserEventBus; app feature depends on chat feature |
| app | DisableBackgroundMode | Disable | Unit | Unit | 2 | 16 | PT+bus | |
| app | DisableBatteryOptimization | Disable | Unit | Unit | 1 | 12 | PT | |
| app | EnableBackgroundMode | Enable | Unit | Unit | 2 | 16 | PT+bus | |
| app | GetAppTheme | Get | Unit | **Flow** | 2 | 22 | poll | no event filter: re-reads on every AppEvent |
| app | GetBackgroundMode | Get | Unit | **Flow** | 2 | 25 | poll | filter BackgroundModeChanged |
| app | SetAppTheme | Set | AppTheme | Unit | 2 | 17 | PT+bus | |
| app | SkipBatteryOptimization | Skip | Unit | Unit | 1 | 12 | PT | `setBatteryOptimizationSkipped(true)` |
| chat | ClearMessages | Clear | Params | Unit | 2 | 18 | orch | repo + ChatNotices |
| chat | ClearSelectedPrivatePeer | Clear | String | Unit | 1 | 17 | rules | compare-and-clear repair of a half-applied DM switch |
| chat | FindMeshPeerByName | Find | String | nullable | 1 | 39 | rules | uses `user.Nicknames` helpers |
| chat | GetAvailableNamedChannels | Get | Unit | value | 1 | 13 | PT | |
| chat | GetChannelKeyCommitment | Get | String | nullable | 1 | 12 | PT | |
| chat | GetChannelMembers | Get | String | value | 1 | 13 | PT | |
| chat | GetGeohashParticipants | Get | String | value | 1 | 12 | PT | |
| chat | GetJoinedChannels | Get | Unit | **Flow** | 2 | 32 | poll | ChannelJoined/Left; `.toSet()` |
| chat | GetJoinedNamedChannels | Get | Unit | **Flow** | 2 | 34 | poll | duplicates `ChatRepository.observeJoinedNamedChannels()`, which has zero callers |
| chat | GetMeshPeers | Get | Unit | value | 1 | 13 | PT | returns `List<GeoPerson>` |
| chat | JoinChannel | Join | Params | **Outcome** | 2 | 125 | rules+orch | `catch (e: Exception)` L117; already-joined branch publishes `ChannelChanged` L47 |
| chat | LeaveChannel | Leave | String | Unit | 3 | 21 | orch | repo + ChatNotices + bus |
| chat | MarkPrivateChatRead | Mark | String | Unit | 1 | 12 | PT | |
| chat | ObserveChannelMessages | Observe | Channel | **Flow** | 2 | 85 | poll ×4 + combine | four inline poll copies; geohash variant combines `observeMiningStatus()`; Meshtastic nodeNum→hex L30 |
| chat | ObserveLatestUnreadPrivatePeer | Observe | Unit | **Flow** | 2 | 32 | poll | |
| chat | ObserveMeshChannelPeople | Observe | (none) | **Flow** | 3 | 201 | rules+orch | **not a `Usecase`**, non-suspend `invoke()`; merges bus + 2 repo flows; swallows RuntimeException L99-105; println L104 |
| chat | ObservePeerSessionStates | Observe | Unit | **Flow** | 1 | 18 | timer poll | `delay(1_000)` loop L13-16; `Map<String,String>` |
| chat | ObservePrivateChats | Observe | Unit | **Flow** | 2 | 33 | poll | |
| chat | ObserveSelectedPrivatePeer | Observe | Unit | **Flow** | 2 | 32 | poll | |
| chat | ObserveUnreadPrivatePeers | Observe | Unit | **Flow** | 2 | 32 | poll | |
| chat | ProcessChatCommand | Process | ChatCommandRequest | CommandResult | 0 | 103 | rules (pure parser) | KDoc L12-15 names `presentation:viewvo/CommandSuggestions.kt`; UI string "large trout" L28 |
| chat | ResolveChatFallback | Resolve | Channel? | value | 2 | 41 | nav | back-navigation target; KDoc mentions "Compose leave button and back" |
| chat | SendMessage | Send | Params | Unit | 1 | 27 | PT | |
| chat | SendPrivateMessage | Send | Params | Unit | 1 | 22 | PT | **dead** |
| chat | SetChannelPassword | Set | Params | **Outcome** | 2 | 109 | rules | `catch (e: Exception)` L53, L102; `verifyPasswordOwnership` result ignored L93-96 |
| init | GetAppInformation | Get | Unit | value | 1 | 11 | PT | returns injected constant |
| init | InitializeApplication | Initialize | Unit | Unit | 2 | 19 | launch | fan-out on `contextFacade.default`, joined |
| location | BeginGeohashSampling | Begin | List<String> | Unit | 1 | 12 | PT | |
| location | EndGeohashSampling | End | Unit | Unit | 1 | 12 | PT | |
| location | GetAvailableChannels | Get | Unit | value | 1 | 15 | PT+rule | filters out BUILDING level |
| location | GetBookmarkNames | Get | Unit | value | 1 | 12 | PT | |
| location | GetBookmarkedChannels | Get | Unit | value | 1 | 12 | PT | |
| location | GetLastFixInfo | Get | Unit | nullable | 1 | 13 | PT | |
| location | GetLocationGeohash | Get | GeohashChannelLevel | value | 1 | 14 | PT | `LocationUnavailableException` passes through |
| location | GetLocationNames | Get | Unit | value | 1 | 13 | PT | |
| location | GetLocationServicesEnabled | Get | Unit | Boolean | 1 | 12 | PT | one-shot twin of ObserveLocationServicesEnabled |
| location | GetParticipantCounts | Get | Unit | value | 2 | 21 | orch | LocationRepo + ChatRepo |
| location | GetPermissionState | Get | Unit | value | 1 | 14 | PT | one-shot twin of ObservePermissionState |
| location | GetTeleportState | Get | Unit | Boolean | 1 | 12 | PT | |
| location | ObserveChannelParticipants | Observe | Unit | **Flow** | 6 | 134 | orch+rules | merges 3 buses; reads user state for current channel; `Clock.System.now()` L119 |
| location | ObserveCurrentChannelBookmarkState | Observe | Unit | **Flow** | 3 | 54 | poll | read derives channel from UserRepository |
| location | ObserveHasNotes | Observe | Unit | **Flow** | 3 | 66 | poll | comment names `MainViewModel` L52; `hasNotes` hard-coded-false workaround |
| location | ObserveLocationServicesEnabled | Observe | Unit | **Flow** | 2 | 31 | poll | |
| location | ObserveNotes | Observe | String | **Flow** | 2 | 32 | poll | param used in read, **not** in filter |
| location | ObservePermissionState | Observe | Unit | **Flow** | 2 | 32 | poll | |
| location | RequestLocationPermission | Request | Unit | Unit | 2 | 17 | PT+bus | |
| location | ResolveLocationName | Resolve | Params | nullable | 1 | 19 | PT | |
| location | SendNote | Send | Params | Unit | 2 | 27 | PT+bus | |
| location | ToggleBookmark | Toggle | String | Unit | 2 | 16 | PT+bus | |
| location | ToggleLocationServices | Toggle | Unit | Unit | 2 | 16 | PT+bus | |
| lora | GetLoRaSettings | Get | Unit | value | 1 | 14 | PT | |
| lora | SetLoRaEnabled | Set | Boolean | Unit | 1 | 13 | PT | |
| lora | SetLoRaRegion | Set | Params | **Boolean** | 2 | 20 | orch | LoRaSettingsRepo + `ChatRepository.reconfigureLoRa` |
| lora | SetLoRaTxPower | Set | Params | **Boolean** | 2 | 20 | orch | same shape as SetLoRaRegion |
| lora | SetShowLoRaPeers | Set | Boolean | Unit | 1 | 13 | PT | |
| lora | SwitchLoRaProtocol | Switch | LoRaProtocolType | **Boolean** | 2 | 17 | orch | passes `param.name` (enum→String) to repo L15 |
| nostr | GetPowSettings | Get | (none) | **Flow** | 2 | 25 | poll | **not a `Usecase`**; consumes a rendezvous `ReceiveChannel` via `receiveAsFlow()` |
| nostr | SetPowSettings | Set | PowSettings | Unit | 2 | 19 | rule+bus | **not a `Usecase`**; `coerceIn(0,32)`; `update()` on the rendezvous Channel can suspend forever |
| tor | DisableTor | Disable | Unit | Unit | 4 (1 opt) | 34 | orch | intent + bus + repo, optional TorRouteLifecycle |
| tor | EnableTor | Enable | Unit | Unit | 5 (1 opt) | 45 | launch | `applicationScope.launch { torRepository.enable() }` L40-43 |
| tor | GetTorAvailability | Get | Unit | value | 1 | 17 | PT | |
| tor | GetTorMode | Get | Unit | **Flow** | 2 | 32 | poll | **dead** |
| tor | GetTorStatus | Get | Unit | **Flow** | 2 | 54 | poll+rule | requested mode qualified by availability; comments about "header"/"settings switch" L24-31 |
| tor | ObserveRequestedTorMode | Observe | Unit | **Flow** | 1 | 19 | PT | returns `RequestedTorIntent.updates` directly |
| user | BlockUser | Block | Request | Unit | 1 | 23 | disp | |
| user | GetAllFavorites | Get | Unit | value | 1 | 13 | PT | |
| user | GetBlockedUsers | Get | Unit | value | 1 | 14 | PT | |
| user | GetUserNickname | Get | Unit | **Flow** | 2 | 41 | poll+rule | `"anon"` default L38 (duplicated in SaveUserStateAction) |
| user | GetUserState | Get | Unit | value | 3 | 35 | rules (gating) | println L32; top-level `DEFAULT_CHANNEL` L12 |
| user | IsUserBlocked | Is | Request | Boolean | 1 | 22 | disp | **dead**; predicate name |
| user | SaveUserStateAction | Save | UserStateAction | Unit | 8 | 192 | nav+orch | screen state machine with back-stack; see review §2 |
| user | SetUserNickname | Set | String | Unit | 2 | 17 | PT+bus | |
| user | ToggleFavorite | Toggle | Params | value | 3 | 45 | orch+rule | |
| user | UnblockUser | Unblock | Request | Unit | 1 | 22 | disp | |

**Totals.** 82 classes · 22 return `Flow` (14 `Observe*` + 8 `Get*`) · 2 return `Outcome` · 3 return `Boolean` as failure · 40 contain no logic (29 PT, 8 PT+bus, 3 disp).

## The bus-poll copies

The template `channelFlow { bus.events().onStart { send(read()) }.collect { e -> if (matches(e)) send(read()) } }` appears in 17 classes plus four inline copies in `ObserveChannelMessages` (21 in all). `D/` = `domain/src/commonMain/kotlin/com/bitchat/domain/`.

| file:lines | bus | events reacted to | param in filter? | extra |
|---|---|---|---|---|
| `D/app/GetAppTheme.kt:15-21` | App | **all** (no filter) | – | double-reads on subscribe |
| `D/app/GetBackgroundMode.kt:16-24` | App | BackgroundModeChanged | – | |
| `D/chat/GetJoinedChannels.kt:16-31` | Chat | ChannelJoined, ChannelLeft | – | `.toSet()` |
| `D/chat/GetJoinedNamedChannels.kt:17-33` | Chat | ChannelJoined, ChannelLeft, ChannelListUpdated | – | repo already has `observeJoinedNamedChannels()` |
| `D/nostr/GetPowSettings.kt:16-24` | Nostr (`ReceiveChannel`) | all | – | `receiveAsFlow()` + `map`; single consumer |
| `D/tor/GetTorMode.kt:17-31` | Tor | ModeChanged | – | dead |
| `D/tor/GetTorStatus.kt:19-53` | Tor | StatusChanged, ModeChanged | – | composite read |
| `D/user/GetUserNickname.kt:17-33` | User | NicknameUpdated, ProfileUpdated, LoginChanged | – | composite read |
| `D/chat/ObserveLatestUnreadPrivatePeer.kt:16-30` | Chat | LatestUnreadPrivatePeerChanged | – | |
| `D/chat/ObservePrivateChats.kt:17-31` | Chat | PrivateChatsUpdated | – | |
| `D/chat/ObserveSelectedPrivatePeer.kt:17-30` | Chat | SelectedPrivatePeerChanged | – | |
| `D/chat/ObserveUnreadPrivatePeers.kt:17-30` | Chat | UnreadPrivatePeersUpdated | – | |
| `D/location/ObserveCurrentChannelBookmarkState.kt:21-36` | Location | ChannelChanged, BookmarksChanged | – | read consults UserRepository |
| `D/location/ObserveHasNotes.kt:21-36` | Location | ChannelChanged, NotesChanged | – | read consults UserRepository |
| `D/location/ObserveLocationServicesEnabled.kt:16-30` | Location | LocationServicesChanged | – | |
| `D/location/ObserveNotes.kt:17-31` | Location | NotesChanged | **no** | |
| `D/location/ObservePermissionState.kt:17-31` | Location | PermissionStateChanged | – | |
| `D/chat/ObserveChannelMessages.kt:56-64` (mesh) | Chat | MeshMessagesUpdated | – | |
| `D/chat/ObserveChannelMessages.kt:66-74` (DM) | Chat | PrivateChatsUpdated | no (indexes map by peerID) | |
| `D/chat/ObserveChannelMessages.kt:76-84` (named) | Chat | NamedChannelMessagesUpdated | **yes** | |
| `D/chat/ObserveChannelMessages.kt:37-54` (geohash) | Chat | GeohashMessagesUpdated | **yes** | `combine(…, observeMiningStatus())` |

Non-template stream use cases: `ObserveMeshChannelPeople.kt:64-107` (`withIndex` to treat the replayed event as "everything changed", `merge` of bus + `observeLoRaPeerChanges()` + `observeBlockList()`, `distinctUntilChanged`), `ObserveChannelParticipants.kt:45-84` (`merge` of three buses into a private `Trigger` type with a stateful channel switch), `ObservePeerSessionStates.kt:12-17` (one-second timer, no bus), `ObserveRequestedTorMode.kt:18` (StateFlow pass-through).

Differences between the copies that a shared helper has to decide once: every in-memory bus is `MutableSharedFlow(replay = 1)`, so a subscriber gets the `onStart` read plus a replayed event (copies with a filter double-read only when the replayed event matches; `GetAppTheme` and `GetPowSettings` always do); `channelFlow` adds a 64-element buffer that a plain `flow` would not; `InMemoryChatEventBus.update` switches to `io` while the others switch to `default`; only `ObserveMeshChannelPeople` de-duplicates identical snapshots; bus accessor names differ (`getAppEvent(): SharedFlow`, `getNostrEvent(): ReceiveChannel`, `events(): Flow`).

## Tests whose file name matches no class

| test | actually tests | verdict |
|---|---|---|
| `jvmTest/.../user/MeshNicknamesTest.kt` | top-level functions in `D/user/Nicknames.kt` | helper test; name mismatch |
| `jvmTest/.../user/SaveUserStateActionPreviousChannelTest.kt` | `SaveUserStateAction` | scenario-suffixed |
| `jvmTest/.../user/SaveUserStateActionMeshDmNameTest.kt` | `SaveUserStateAction` | scenario-suffixed |
| `jvmTest/.../lora/ReconfigureLoRaSettingsTest.kt` | `SetLoRaRegion` **and** `SetLoRaTxPower` | named after the repo method; describes one `ReconfigureLoRa` use case |
| `jvmTest/.../location/ObserveGeohashPeopleTest.kt` | class inside is `ObserveChannelParticipantsTest` | **stale file name** |
| `jvmTest/.../tor/GetTorStatusModeTest.kt` | `GetTorStatus` | scenario-suffixed |
| `jvmTest/.../tor/DisableTorRouteRecoveryTest.kt` | `DisableTor` | scenario-suffixed |

---
title: Local review of the fifth slice (D30: ObserveUserState, ObserveFavorites, MainViewModel)
date: 2026-10-14
tags: [bitchatkmp, architecture, review, local]
related:
  - "[[2026-10-13-local-review-d29]]"
---

# Local review of the fifth slice (D30)

- **Who:** local-claude, with Codex (`gpt-6-astra`, high effort): one review of the code, the local switch and the writer tables, and a second round on the fixes. local-claude built every target.
- **What:** `claude/observe-user-state` at `347e193`, one commit on `6aefc15`, put onto the owner's `main` at `845c44c`. That `main` had three commits the cloud session had not seen: `c2866a4` (a board's city from its public address), `7006a10` (a Curl transfer that breaks after its headers), `845c44c` (a saved place as this device's location). None adds a writer of the user state or of the favourites, or a publisher of a `UserEvent`.
- **Then the local half of D30:** `MainViewModel` collects the two use cases instead of `UserEventBus`.
- **Result:** the branch built and passed as pushed, and the writer tables are right in substance. Two real faults came out once the view model used the use cases. Both are fixed. A third was not taken (row 3).

## 1. What was found

| # | Found by | What | Done |
|---|---|---|---|
| 1 | Codex | **An event published during the first read could be lost.** Both use cases read in `onStart`, before they subscribe. If a matching event is published during that read and an unrelated one then takes the bus's single replay slot, the observer subscribes, sees only the unrelated one, and never reads again. The old `MainViewModel` collector subscribed first: `onStart { goToNextStep() }` only launched the read | Each use case subscribes first, then reads. An `UNDISPATCHED` launch forwards the bus into a `Channel(BUFFERED)` and takes the replay value in the same step; the reads then handle that channel in order. A new test in each class holds the first read, publishes a matching then an unrelated event, and expects the second read. Both failed on the first version and pass now |
| 2 | Codex | **`toggleFavorite`'s own refresh could show an older map.** It read the favourites alongside the observer: its read could start first and finish last, and then overwrite the newer map. The old code read inside `_headerState.update { }`, whose retry happened to read again | That refresh is gone. `ToggleFavorite` publishes `FavoriteStatusChanged` on every path on which the refresh used to run, so the observer alone shows favourites, in order. `GetAllFavorites` had no caller left and is deleted |
| 3 | Codex | **A failed read now ends state observation for the view model's life.** Before, an exception from `GetUserState` ended one launched `goToNextStep()`; the bus collector lived on | **Not taken.** On Android and iOS an exception that leaves a `viewModelScope` coroutine ends the app, before and after. The JVM desktop apps and the board's terminal UI log it and go on (the terminal UI installs its own hook, `apps/embedded/tui/.../Posix.kt`). There every other observer in this view model (`ObserveAppTheme`, `ObserveUserNickname`, `ObserveJoinedChannels`, …) already stops at its first exception. Catching inside these two use cases would be a policy for two observers only, and on Android and iOS it would turn a crash into a screen that silently stops following. What an observer does with a failed read belongs in the D5 proposal |

## 2. The local switch

- **State:** `observeUserState().collect { viewModelScope.launch { showUserState(it) } }`. Each state is shown in its own coroutine, as `goToNextStep()` did, because `navigation` is a rendezvous channel. A navigation nobody has taken yet must not hold up the next state. A test shows the header follows a second state while the first navigation waits; it fails if the state is shown inline.
- **Favourites:** `observeFavorites().collect(::showFavorites)`, where the one-shot read at the end of `init` was. A test shows each emitted map reaches the header.
- `goToNextStep()` stays, for the wipe: it reads once and shows the result.
- **Reads per event, at start, and with a matching event in the replay slot:** the same counts as before.
- Five changes were each checked to turn a test red: a state shown inline, favourites not shown, states not shown, `LoginChanged` dropped, favourites read again on every event.
- **What changes for a user:** an event published during the first read is no longer lost, and a toggle refreshes the favourites once instead of twice.

## 3. The writer tables

Right in substance. The three main findings are confirmed in the code: F1 (the system changing a permission, Bluetooth, location services or battery optimisation publishes no `UserEvent`), F2 and F3 (`UserRepo.clearData()` sets a new nickname and clears the favourites; `ClearAllData` then publishes only `StateChanged`).

Corrections:

- **The location row mixes two settings.** `GetUserState` reads the platform's location services through `ConnectivityRepository`. `ObserveLocationServicesEnabled` reads the app's own preference through `LocationRepository` and does not see the platform input. Likewise for Bluetooth, the connectivity repositories read it as well as `ChatRepo`.
- **F1, "every navigation runs `SaveUserStateAction`."** Not every one: `showAppInfo()` and `showLocationChannels()` send a navigation directly. A system change is seen at the next read of the state, not at the next navigation.
- **"`goToNextStep()` has two callers besides the bus: the wipe and the replay."** The replay arrives through the bus. Before the switch the other callers were `onStart` and the wipe; now only the wipe calls it.
- **"Texts and behaviour: none changed."** True of the cloud commit alone. With the switch the changes are those in section 2.

## 4. Verification

On the tree that landed, by local-claude on macOS arm64:

- `scripts/verify.sh full`: OK.
- `scripts/tui-smoke.py`: PASS.
- `:domain:jvmTest` with 16 tests in the two new classes, `:presentation:viewmodel:jvmTest`, and `:apps:desktop:test`, whose desktop Koin check covers the changed constructor.

`e5c13ce` (the use cases) was not built on its own; the tree with both commits was.

## 5. Q12 and the next slices

**Q12, the wipe's missing events.** Recommended: yes.
- Publish `NicknameUpdated` right after `userRepository.clearData()`, inside the barrier, as `ThemeUpdated` is published after the app store.
- For the favourites add a `UserEvent.FavoritesCleared` that `ObserveFavorites` reads again on. `FavoriteStatusChanged` needs a peer id, and one event per cleared key would mean reading the favourites before clearing them.
- `LoRaAppInitializer` matches `UserEvent` exhaustively, so a new event needs a line in `data:repo`. Only a local build sees that.

**Next slices,** in order. The owner chooses (Q13).
1. **Q12's fix, and `ChatViewModel` off `ChatEventBus` (cloud for the domain half).** `ChatViewModel` reads the user state on start and on every `ChannelChanged`, the same shape as D30. A use case for it subscribes first and keeps every event, since the comment there explains why repeats matter. Local agents switch the view model. After that no view model imports a bus, and one of the waiting Konsist rules can hold that: no view model imports an `eventbus` or a `repository` package.
2. **The older observers subscribe before their first read (cloud, domain only).** `ObserveUserNickname`, `ObserveJoinedChannels`, `ObserveAppTheme` and the others read in `onStart` and have the window of row 1.
3. **A written proposal for the rest of D5,** now including what an observer does when a read fails (row 3).
4. **Model moves with their file names.** Local build needed.

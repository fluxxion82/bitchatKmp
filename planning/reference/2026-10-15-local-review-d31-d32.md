---
title: Local review of the sixth slice (D31 to D34: wipe events, ObserveActiveChannel, ChatViewModel)
date: 2026-10-15
tags: [bitchatkmp, architecture, review, local]
related:
  - "[[2026-10-14-local-review-d30]]"
---

# Local review of the sixth slice (D31 to D34)

- **Who:** local-claude, with Codex (`gpt-6-astra`, high effort): one review of the code, the local half and the writer table, and a second round on the test change. local-claude built every target.
- **What:** `claude/wipe-events-and-chat-state` at `6ffa808` (two commits on `fd93095`), put onto the owner's `main` at `2cfc52e`.
- **Local half, done before the review this time:**
  - the `LoRaAppInitializer` line;
  - `ChatViewModel` collects `ObserveActiveChannel` instead of `ChatEventBus`;
  - the Konsist rule that no view model names an `eventbus` or `repository` package.
- **Result:** both commits built and passed once the `data:repo` line was in, and neither needed a production change. One test weakness was fixed. Two things older than the slice came out: one is filed as its own task, one is recorded.

## 1. What was found

| # | Found by | What | Done |
|---|---|---|---|
| 1 | Codex | **The wipe tests passed with the publishes in the wrong place.** The mocked user store gave the new nickname and no favourites on its second read whatever happened, so the tests passed even with the publishes moved before `userRepository.clearData()` | The mock answers the new values only after the wipe's `clearData()` has run. Four changes each fail the tests they should: publishes before the store write, `NicknameUpdated` removed, `FavoritesCleared` removed, publishes after the app store |
| 2 | local-claude, Codex | **`ChatRepo` stops following Bluetooth after an unrelated user event.** Its mesh-run `combine` keeps the latest user event and decides only on `StateChanged` or `LoginChanged`. After a nickname change or a favourite toggle, switching Bluetooth off does not stop the mesh, and switching it on does not start it, until the next state change. Older than this slice. D31 only adds the case of a wipe that fails after the user store | Not in this slice. Filed for the owner as its own task: filter the user events before `combine` |
| 3 | Codex | **The wipe can wait for LoRa's first start.** `LoRaAppInitializer` handles `StateChanged` inline and its first `tryStartLoRa()` can take about 20 s. While it does, the wipe's second publish inside the barrier waits for it | Recorded, not changed. It is bounded, happens once per process and only on a build with a LoRa transport, and no lock cycle was found. `SaveUserStateAction` and `ToggleFavorite` already wait the same way. Running the bootstrap apart from the collector is a data-layer change |
| 4 | Codex | **The view-model rule is a text search.** A comment that names such a package fails it, and a repository reached through a use case's return value passes it | Recorded. The rule's name says what it checks. No production file matches today; putting back the old bus import fails it |

## 2. The local half

- **`ChatViewModel`:**
  - For `ActiveChannel.Chat` it updates the header fields (`selectedLocationChannel`, `currentGeohash`, `currentChannel`). For every result it sets the followed channel to `active.channel`.
  - That is the old collector's behaviour for every `UserState`. Codex checked the mapping case by case.
  - `MeshForLoRa` follows the mesh's messages and leaves the header alone, as before.
  - The old `println` for Bluetooth off is gone.
  - New: an event published during the first read is no longer lost.
- **`ChatViewModelChannelSwitchTest`** builds a real `ObserveActiveChannel` over its real bus. A new case shows Bluetooth off following the mesh with the header unchanged. Changes caught: the header updated for every channel, only a chat followed.
- **`LoRaAppInitializer`** matches `FavoritesCleared`. Because it re-syncs the radio identity on `NicknameUpdated`, the LoRa heartbeat now carries the nickname the wipe gave. Before, it kept the old one until a restart.
- **The Konsist rule** (`noViewModelSourceNamesAnEventBusOrARepositoryPackage`) fails on an import of a bus and on a repository named in code. Both changes were tried. No view model imports a bus any more.

## 3. The writer table

Right. Codex found no writer and no `ChannelChanged` publisher missing, in any layer or in `main`'s six later commits.

One correction to G1: `SaveUserStateAction` calls `setUserState(blocking)`, but `LocalUserPreferences` clears the saved state for the permission, Bluetooth and location states instead of storing them. G1's point stands: no `ChannelChanged` follows, so `ChatViewModel` keeps its channel until the next one. That is today's behaviour, and the switch did not show a reason to change it.

## 4. Verification

On the tree that landed, by local-claude on macOS arm64:

- `scripts/verify.sh full`: OK.
- `scripts/tui-smoke.py`: PASS.
- `:domain:jvmTest` 216, `:presentation:viewmodel:jvmTest` 97, `:data:repo:desktopTest` 381, `:apps:desktop:test` (the desktop Koin check), all green.
- Eight changes each turned a test red (sections 1 and 2).

The three commits were built together, not one by one.

## 5. Q14 and the next slices

**Q14, does "verb + the model it gives" apply to the use cases already there?** Recommended: (a), new use cases only, written in `CONVENTIONS.md`.
- An existing one is renamed or given a model only when a slice touches it for another reason.
- A Konsist rule (c) would need most of today's 18 `Observe*` use cases as exceptions. The name/type pairs are not mechanical (`ObserveFavorites` gives a map of `FavoriteRelationship`), so such a rule would mostly guard its own exception list.

**Next slices,** in order. The owner chooses (Q15).
1. **The older observers subscribe before their first read (cloud, domain only).** `ObserveUserNickname`, `ObserveJoinedChannels`, `ObserveAppTheme` and the rest still read first, and have the window D30 closed for the new ones.
2. **A written proposal for the rest of D5 (cloud, no build).** What each use case that lets an exception through would return, and what an observer does when a read fails.
3. **Model moves with their file names (local build needed).** `UserState` and `ActiveState` to `user/model` (this also renames `AppState.kt`), and `GeoPoint` to its own file.

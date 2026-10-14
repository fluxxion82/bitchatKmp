---
title: Status
tags: [bitchatkmp, planning, status]
---

# Status

Updated 2026-10-13 by local-claude (D28 and D29 pushed; D30 decided).

## In flight

| Branch | Holds | State | Waiting on |
|---|---|---|---|
| `main` | `6aefc15`. Since `ff4697c`: three commits of the owner's side (`c923790` REST client engine close, `e1416cc` string resources, `b572169` the default place for location), then D28 and D29: `dca893b` (the two placement rules), `519ba25` (four file names), `6aefc15` (the rules cannot be skipped by the build cache or a `@Suppress`) | Pushed 2026-10-13 | cloud-claude: start D30 from here |

## D28 and D29, as landed

Both branches built and passed as pushed, and neither needed a change. The review is in `reference/2026-10-13-local-review-d29.md`. One commit was added, `6aefc15`, for two faults older than the branches:

- **The build did not run the rules again after some source changes.** Konsist reads sources, Gradle compared classes: a misplaced interface in `linuxMain` passed from the build cache. Both modules with an `ArchitectureTest` now declare `src` as an input of `jvmTest`.
- **A `@Suppress` in the checked code switched a rule off.** Konsist's `assertTrue` and `assertFalse` leave out a declaration annotated `@Suppress` with the calling function's name. Neither `ArchitectureTest` uses them any more.

For the next rule: **use `assertEach` in the domain's `ArchitectureTest`, not Konsist's assertions.** It also asserts that the rule selected something. Both points are in `CONVENTIONS.md` under Verification.

## What `main` has that the cloud session has not seen

From `b572169`, in the domain:

- `LocationRepository.getAvailableGeohashChannels()` and `GetAvailableChannels` return `NearbyChannels(channels, fix)`.
- `LocationRepository.getLastFixInfo()` and the use case `GetLastFixInfo` are gone.
- New in `location/model`: `NearbyChannels`, `DefaultPlace.kt`, `LocationFixInfo.Source.DEFAULT`, `LocationFixInfo.unavailableReason`.

`c923790` and `e1416cc` do not touch the domain.

## What was built and run

By local-claude on macOS arm64, on the tree that landed:

- `scripts/verify.sh full`: OK. That is `quick`, `desktop` (with `packageDmg`), `android`, `ios`, `embedded` and `tui` (debug and release links for linuxArm64), `desktop-tui`.
- Module tests, all green: `:domain:jvmTest` (183 tests with the five rules, 2 skipped), `:presentation:viewmodel:jvmTest` (79), `:data:repo:desktopTest` (331), `:apps:desktop:test`, and with `-Ptui.enabled=true` the terminal UI tests.
- `scripts/tui-smoke.py`: the desktop terminal UI starts from the real Koin graph and shows its four screens.
- Plants: listed in the review, section 2. Every must-fail plant fails without `--rerun`; the clean tree passes.
- `dca893b` and `519ba25` were built together, before the third commit, on every target; they were not built one by one.

Not run: any app on a phone or a board.

## Asks

1. **cloud-claude: the next slice is decided (D30).** Fetch `main` at `6aefc15`. Read `reference/2026-10-13-local-review-d29.md` (section 5 lists what `main` gained) and the two new lines under Verification in `CONVENTIONS.md`. Put the plan in `LOG.md` first. One branch, inside `:domain`.

   **Why.** `MainViewModel` collects `UserEventBus` itself: on start, `StateChanged` and `LoginChanged` it reads the user state with `GetUserState`; on `FavoriteStatusChanged` it reads the favourites with `GetAllFavorites`. A viewmodel should take use cases, not a bus.

   **First, in the plan: the writers.** Before any code, list every writer of what each observer will read, in every layer, and say whether it publishes the event (the rule under Observers and events).
   - For the user state: everything `GetUserState` reads. That is the saved state in `UserRepository`, and also the permissions, Bluetooth, location services and battery optimisation answers of `AppRepository` and `ConnectivityRepository`. Name what changes each and what, if anything, publishes `StateChanged` afterwards.
   - For the favourites: `ToggleFavorite`, the favourite notifications handled in `data:repo`, the trim at start, the wipe.
   - A writer that publishes nothing is a finding. Report it; do not fix it in `data:*`.

   **Then the two use cases.**
   - `ObserveUserState : Usecase<Unit, Flow<UserState>>`. It emits what `GetUserState` returns on start, and again on `StateChanged` and on `LoginChanged`. It takes `GetUserState`; it does not copy its rules.
   - `ObserveFavorites : Usecase<Unit, Flow<Map<String, FavoriteRelationship>>>`. On start and on `FavoriteStatusChanged`.
   - Same shape as `ObserveUserNickname`. No `distinctUntilChanged` and no conflation: today every matching event runs the viewmodel's refresh, and the local switch must not change that unnoticed.
   - Their lines in `domainModule`. `GetUserState` and `GetAllFavorites` stay; they have other callers.

   **Tests,** like `ObserveUserNicknameTest`: the first value; one more read for each matching event; no read for an unrelated event, also when it sits in the replay slot at start; two matching events give two reads. A test that records behaviour nobody decided says "today" in its name.

   **Not in this branch:** `MainViewModel` or anything else outside `:domain`. Local agents switch the viewmodel afterwards and run the desktop Koin check. `ChatViewModel` and `ChatEventBus` are a later slice.

   Report what the scratch build ran. For anything planted, read the task's outcome before believing a pass.

## Open questions

- **Q12 (D30, `6aefc15`). Should the wipe publish what it changes, as it now does for the theme?** `ClearAllData` clears the favourites and gives the user a new nickname. It publishes neither `FavoriteStatusChanged` nor `NicknameUpdated`, so the sidebar keeps the old favourites and the header the old nickname until something else changes them or the app restarts (F2 and F3 in `LOG.md`, 2026-10-14). The fix is two lines in `ClearAllData`, the domain use case, next to its `ThemeUpdated`. `FavoriteStatusChanged` takes a peer id, so the event would need a key for "all", or a separate event. That choice is the owner's. Not part of D30's branch.

## Answered

- **Q1.** `default` stays on the main queue on iOS for now (D17).
- **Q2.** `GetAppTheme` and `GetUserNickname`; no single read of background mode (D18).
- **Q3, Q4.** LoRa hidden where no protocol is registered; an unsupported saved protocol reads as the fallback (D19).
- **Q5.** The `ChatRepository` methods only tests called are removed (D20).
- **Q6.** B is a Konsist architecture test (D25).
- **Q7.** The changed texts are accepted (D26).
- **Q8.** Domain tests for the error contract, then the narrow observer fixes (D27). Done, on `main`.
- **Q9.** The two placement rules inside `:domain` now; the other three candidates wait (D28, the owner, 2026-10-13).
- **Q10.** Next: those two rules, then the file names that differ from their class (D29, the owner, 2026-10-13). Done, on `main`.
- **Q11.** Next: `ObserveUserState` and `ObserveFavorites` in the domain (D30, the owner, 2026-10-13).

## Next

- **D5 is partly done.** Channel and LoRa commands return typed failures. Exceptions still pass through: discovery, verification, the password write and the join in `JoinChannel`'s existing-channel path, and all three LoRa commands (tests pin that those propagate). Turning any into an `Outcome` changes a tested contract: write the mapping and the texts first.
- **Two viewmodels import a bus:** `MainViewModel` (`UserEventBus`, D30 is its domain half) and `ChatViewModel` (`ChatEventBus`, not started).
- **File names:** `app/model/AppState.kt` holds `UserState` and `ActiveState`; rename it when those move to `user/model`. The rest is in the review's section 6.
- **Unlocked collections** in `ChatRepo`, `LocalChannelPreferences`, `NostrClient` and the Apple Bluetooth services: local work, design first.
- **Open from the plan:** (d) pass-through use cases, (e) scopes and dispatchers, (f) a transport port; where tracked rules live. Plan §3 is a list of old proposals, not instructions (see the note at the top of the plan).

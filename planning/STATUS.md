---
title: Status
tags: [bitchatkmp, planning, status]
---

# Status

Updated 2026-10-15 by cloud-claude (D31 and D32 pushed).

## In flight

| Branch | Holds | State | Waiting on |
|---|---|---|---|
| `main` | `fd93095`. Since `6aefc15`: three commits of the owner's side (`c2866a4` a board's city from its public address, `7006a10` Curl transfers, `845c44c` a saved place as this device's location), then D30: `e5c13ce` (`ObserveUserState`, `ObserveFavorites`), `fd93095` (`MainViewModel` uses them) | Pushed 2026-10-14 | |
| `claude/wipe-events-and-chat-state` | D31 (`651f462`): the wipe publishes `NicknameUpdated` and `FavoritesCleared`. D32 (`6ffa808`): `ObserveActiveChannel` and the `ActiveChannel` model (D33, D34). Two commits on `fd93095` | Pushed 2026-10-15 at `6ffa808` | local agents: the `LoRaAppInitializer` line, build and review, then switch `ChatViewModel` |

## D30, as landed

The branch built and passed as pushed. The review is in `reference/2026-10-14-local-review-d30.md`.

- **Both use cases subscribe to the bus before their first read.** Read first, a matching event published during that read was lost once an unrelated event took the replay slot. One new test in each class shows it. This is now a line under Observers and events in `CONVENTIONS.md`.
- **`MainViewModel` collects them** and no longer imports `UserEventBus`. Each state is shown in its own coroutine, because `navigation` is a rendezvous channel.
- **`toggleFavorite` no longer refreshes the favourites itself.** That read could show an older map after the observer had shown a newer one. `GetAllFavorites` had no caller left and is deleted.
- **Not taken:** making the observers survive a failed read. On Android and iOS such an exception ends the app before and after. Elsewhere every other observer in the view model already stops at its first one. It goes into the D5 proposal.

## What was built and run

By local-claude on macOS arm64, on the tree that landed:

- `scripts/verify.sh full`: OK. That is `quick`, `desktop` (with `packageDmg`), `android`, `ios`, `embedded` and `tui` (debug and release links for linuxArm64), `desktop-tui`.
- `scripts/tui-smoke.py`: the desktop terminal UI starts from the real Koin graph and shows its four screens.
- Module tests, all green, among them `:domain:jvmTest`, `:presentation:viewmodel:jvmTest` (with the new `MainViewModelUserStateTest`) and `:apps:desktop:test` (the desktop Koin check).
- Five changes each turned a test red (review, section 2).
- `e5c13ce` was not built on its own; the tree with both commits was.

Not run: any app on a phone or a board.

## Asks

1. **Local agents: on `claude/wipe-events-and-chat-state` (`6ffa808`), add `UserEvent.FavoritesCleared -> Unit` to the exhaustive `when` in `data/repo/.../di/LoRaAppInitializer.kt:63`, which cloud-claude cannot build. Then build and review, and switch `ChatViewModel` to `ObserveActiveChannel`** as D32 to D34 say. The writers, G1 and G2 are in `LOG.md` (2026-10-15, plan); the use case as pushed, and what was built and run, are in the entries "D33" and "D34" after it.
2. **Owner:** Q14; and G1, if the local switch shows `ChatViewModel` should also follow `StateChanged`.

## Open questions

- **Q14 (D34). Does "verb + the domain model it gives" apply to the use cases already there?** Of the 24 `Observe*` use cases on `main`, only these give a model of the name they carry: `AppTheme`, `BackgroundMode`, `PermissionState`, `PowSettings`, `TorStatus`, `UserState`, and on the branch `ActiveChannel`. Some give a list of the model they name, which could count as fitting: `ObserveNotes` gives `List<Note>`. The rest give a primitive, a collection of ids, or a model of another name:
  - `ObserveUserNickname`: `String`.
  - `ObserveHasNotes`, `ObserveCurrentChannelBookmarkState`, `ObserveLocationServicesEnabled`: `Boolean`.
  - `ObserveSelectedPrivatePeer`, `ObserveLatestUnreadPrivatePeer`: `String?`.
  - `ObserveJoinedChannels`, `ObserveUnreadPrivatePeers`: `Set<String>`.
  - `ObservePeerSessionStates`: `Map<String, String>`.
  - `ObservePrivateChats`: `Map<String, List<BitchatMessage>>`.
  - `ObserveFavorites`: `Map<String, FavoriteRelationship>`.
  - `ObserveChannelMessages`: `List<BitchatMessage>`.
  - `ObserveChannelParticipants`: `List<GeoPerson>`.
  - `ObserveMeshChannelPeople`: `List<MeshChannelPerson>`.
  - `ObserveJoinedNamedChannels`: `List<ChannelInfo>`.
  - `ObserveRequestedTorMode`: `TorMode`.

  Options: (a) the rule is for new use cases only; (b) a list of the model counts, and the rest get models or new names over time; (c) a Konsist rule that the result type, or its element type, is the name after the verb, with today's exceptions listed. The `Get*` use cases have not been checked. Nothing changes until the owner answers.

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
- **Q11.** Next: `ObserveUserState` and `ObserveFavorites` in the domain (D30, the owner, 2026-10-13). Done, on `main`.
- **Q12.** The wipe publishes `NicknameUpdated` and a new `FavoritesCleared` (D31, the owner, 2026-10-14).
- **Q13.** Next: D31, then the domain half of taking `ChatViewModel` off `ChatEventBus` (D32, the owner, 2026-10-14).

## Next

- **D5 is partly done.** Channel and LoRa commands return typed failures. Exceptions still pass through: discovery, verification, the password write and the join in `JoinChannel`'s existing-channel path, and all three LoRa commands (tests pin that those propagate). Turning any into an `Outcome` changes a tested contract: write the mapping and the texts first.
- **One view model still imports a bus:** `ChatViewModel` (`ChatEventBus`). `MainViewModel` no longer does (D30).
- **The older observers read before they subscribe:** `ObserveUserNickname`, `ObserveJoinedChannels`, `ObserveAppTheme` and the others (see Observers and events in `CONVENTIONS.md`).
- **File names:** `app/model/AppState.kt` holds `UserState` and `ActiveState`; rename it when those move to `user/model`. The rest is in the review's section 6.
- **Unlocked collections** in `ChatRepo`, `LocalChannelPreferences`, `NostrClient` and the Apple Bluetooth services: local work, design first.
- **Open from the plan:** (d) pass-through use cases, (e) scopes and dispatchers, (f) a transport port; where tracked rules live. Plan §3 is a list of old proposals, not instructions (see the note at the top of the plan).

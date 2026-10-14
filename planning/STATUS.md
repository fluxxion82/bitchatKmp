---
title: Status
tags: [bitchatkmp, planning, status]
---

# Status

Updated 2026-10-14 by local-claude (D30 landed locally).

## In flight

| Branch | Holds | State | Waiting on |
|---|---|---|---|
| `main` | Locally `fd93095`; `origin/main` is `845c44c`. Since `6aefc15`: three commits of the owner's side (`c2866a4` a board's city from its public address, `7006a10` Curl transfers, `845c44c` a saved place as this device's location), then D30: `e5c13ce` (`ObserveUserState`, `ObserveFavorites`), `fd93095` (`MainViewModel` uses them) | Landed locally 2026-10-14, not pushed | the owner: the push, Q12, Q13 |
| `claude/observe-user-state` | In `main` as `e5c13ce`, with a fix | To be deleted after the push | the owner |

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

1. **cloud-claude: nothing to build until the owner answers Q12 and Q13.** Read `reference/2026-10-14-local-review-d30.md` and the new line under Observers and events in `CONVENTIONS.md`.

## Open questions

- **Q12. Should the wipe publish what it changes?** Recommended: yes. `NicknameUpdated` right after `userRepository.clearData()`, inside the barrier, and a new `UserEvent.FavoritesCleared` that `ObserveFavorites` reads again on. `LoRaAppInitializer` matches `UserEvent` exhaustively, so the new event needs a line in `data:repo`. The review's section 5 has the reasons.
- **Q13. The next cloud slice.** Recommended: Q12's fix, and the domain half of taking `ChatViewModel` off `ChatEventBus`. Local agents then switch the view model and add the Konsist rule that no view model imports a bus or a repository. The other candidates are in the review's section 5.

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
- **One view model still imports a bus:** `ChatViewModel` (`ChatEventBus`). `MainViewModel` no longer does (D30).
- **The older observers read before they subscribe:** `ObserveUserNickname`, `ObserveJoinedChannels`, `ObserveAppTheme` and the others (see Observers and events in `CONVENTIONS.md`).
- **File names:** `app/model/AppState.kt` holds `UserState` and `ActiveState`; rename it when those move to `user/model`. The rest is in the review's section 6.
- **Unlocked collections** in `ChatRepo`, `LocalChannelPreferences`, `NostrClient` and the Apple Bluetooth services: local work, design first.
- **Open from the plan:** (d) pass-through use cases, (e) scopes and dispatchers, (f) a transport port; where tracked rules live. Plan §3 is a list of old proposals, not instructions (see the note at the top of the plan).

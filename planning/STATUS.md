---
title: Status
tags: [bitchatkmp, planning, status]
---

# Status

Updated 2026-10-15 by local-claude (D31 to D34 landed locally).

## In flight

| Branch | Holds | State | Waiting on |
|---|---|---|---|
| `main` | Locally `5bfbd70`; `origin/main` is `2cfc52e`. Since `fd93095`: six commits of the owner's side (TUI unread chips, BlueZ connects, relayed-link binding, advertisement registration, geohash presence, BlueZ retry), then this slice: `d7a1aa4` (the wipe publishes `NicknameUpdated` and `FavoritesCleared`), `eaac5ff` (`ObserveActiveChannel`, `ActiveChannel`), `5bfbd70` (`ChatViewModel` uses it; the view-model Konsist rule) | Landed locally 2026-10-15, not pushed | the owner: the push, Q14, Q15 |
| `claude/wipe-events-and-chat-state` | In `main` as `d7a1aa4` and `eaac5ff` | To be deleted after the push | the owner |

## D31 to D34, as landed

Both commits built and passed once the `LoRaAppInitializer` line was in; neither needed a production change. The review is in `reference/2026-10-15-local-review-d31-d32.md`.

- **`d7a1aa4`** is your D31 commit, plus the `LoRaAppInitializer` line, plus tighter wipe tests: the mocked user store changes only when the wipe clears it, so a publish made before the write fails them. That is a new line under Observers and events in `CONVENTIONS.md`.
- **`eaac5ff`** is your D34 commit, unchanged.
- **`5bfbd70`**:
  - `ChatViewModel` collects `ObserveActiveChannel`. It updates the header only for `ActiveChannel.Chat` and follows `active.channel` always, which is the old behaviour for every state.
  - The view-model `ArchitectureTest` fails if a production file names a `com.bitchat.domain.<feature>.eventbus` or `.repository` package.
- **Side effect:** `LoRaAppInitializer` re-syncs the radio identity on `NicknameUpdated`, so after a wipe the LoRa heartbeat carries the new nickname.
- **Older than the slice:**
  - `ChatRepo` stops following Bluetooth after an unrelated user event. Filed as its own task.
  - The wipe can wait up to about 20 s for LoRa's first start. Recorded, not changed.

## What was built and run

By local-claude on macOS arm64, on the tree that landed:

- `scripts/verify.sh full`: OK. That is `quick`, `desktop` (with `packageDmg`), `android`, `ios`, `embedded` and `tui` (debug and release links for linuxArm64), `desktop-tui`.
- `scripts/tui-smoke.py`: the desktop terminal UI starts from the real Koin graph and shows its four screens.
- Module tests, all green: `:domain:jvmTest` (216), `:presentation:viewmodel:jvmTest`, `:data:repo:desktopTest` (381), `:apps:desktop:test` (the desktop Koin check).
- Eight changes each turned a test red (review, sections 1 and 2).
- The three commits were not built one by one; the tree with all three was.

Not run: any app on a phone or a board.

## Asks

1. **cloud-claude: nothing to build until the owner answers Q14 and Q15.** Read `reference/2026-10-15-local-review-d31-d32.md` and the new line under Observers and events in `CONVENTIONS.md`.

## Open questions

- **Q14. Does "verb + the model it gives" apply to the use cases already there?** The options are as cloud-claude listed them. Recommended: (a), new use cases only, written in `CONVENTIONS.md`; an existing one changes when a slice touches it for another reason. The reasons are in the review's section 5.
- **Q15. The next cloud slice.** Recommended: the older observers subscribe before their first read (domain only). The other candidates are in the review's section 5.

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
- **No view model imports a bus or a repository** since D32; the Konsist rule in `presentation/viewmodel` holds it.
- **`ChatRepo` stops following Bluetooth after an unrelated user event** (review of D31 and D32, row 2): filed for the owner as its own task.
- **The older observers read before they subscribe:** `ObserveUserNickname`, `ObserveJoinedChannels`, `ObserveAppTheme` and the others (see Observers and events in `CONVENTIONS.md`).
- **File names:** `app/model/AppState.kt` holds `UserState` and `ActiveState`; rename it when those move to `user/model`. The rest is in the review's section 6.
- **Unlocked collections** in `ChatRepo`, `LocalChannelPreferences`, `NostrClient` and the Apple Bluetooth services: local work, design first.
- **Open from the plan:** (d) pass-through use cases, (e) scopes and dispatchers, (f) a transport port; where tracked rules live. Plan §3 is a list of old proposals, not instructions (see the note at the top of the plan).

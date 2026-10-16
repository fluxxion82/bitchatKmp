---
title: Status
tags: [bitchatkmp, planning, status]
---

# Status

Updated 2026-10-16 by local-claude (D36 landed locally).

## In flight

| Branch | Holds | State | Waiting on |
|---|---|---|---|
| `main` | Locally `3953e0c`; `origin/main` is `5bfbd70`. Since `5bfbd70`: two commits of the owner's side, `43215e8` (`ChatRepo` follows Bluetooth after any user event) and `bbbf892` (a private text no Bluetooth link took is reported as failed), then D36: `98a1d50` (every `Observe*` use case on a bus subscribes before its first read), `3953e0c` (the mining status is kept in memory) | Landed locally 2026-10-16, not pushed | the owner: the push, Q16 |
| `claude/observers-subscribe-first` | In `main` as `98a1d50`, with the geohash path added | To be deleted after the push | the owner |

## D36, as landed

The branch built and passed; its 15 observers are right. The review is in `reference/2026-10-16-local-review-d36.md`. Two of the four paths left out did have the window, and both are fixed:

- **The geohash path of `ObserveChannelMessages`** sent its trigger before it subscribed. On the boards, view models run on `Default`, so the read could start before the subscription. It now has the same shape as the others; a test on `Default` fails on the old path.
- **The mining status** that `ObservePowSettings` combines with was not a `StateFlow`. It came from the settings library, which reads first and registers its listener after, so mining that ended in between was never shown. It is a `StateFlow` in `LocalNostrPreferences` now. It is no longer stored, so a crash mid-mining no longer shows "mining" in the next run.

Three lines in `CONVENTIONS.md` under Observers and events: every bus observer subscribes first; other sources an observer combines with need the same care; a race between threads needs threads in its test.

## What was built and run

By local-claude on macOS arm64, on the tree that landed:

- `scripts/verify.sh full`: OK. That is `quick`, `desktop` (with `packageDmg`), `android`, `ios`, `embedded` and `tui` (debug and release links for linuxArm64), `desktop-tui`.
- `scripts/tui-smoke.py`: the desktop terminal UI starts from the real Koin graph and shows its four screens.
- Module tests, all green: `:domain:jvmTest` (243), `:presentation:viewmodel:jvmTest`, `:data:repo:desktopTest`, `:data:local:platform:jvmTest`, `:apps:desktop:test`.
- Run locally: with the old observer code under the new tests, exactly the 17 during-the-first-read tests fail. The old geohash path and the old mining flag each fail their new test.
- The two commits were not built one by one; the tree with both was.

Not run: any app on a phone or a board.

## Asks

1. **cloud-claude: nothing to build until the owner answers Q16.** Read `reference/2026-10-16-local-review-d36.md` and the three lines under Observers and events in `CONVENTIONS.md`.

## Open questions

- **Q16. The next cloud slice.** Recommended: a written proposal for the rest of D5, also covering what an observer does when a read fails (no build). The other candidates are in the review's section 5.

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
- **Q13.** Next: D31, then the domain half of taking `ChatViewModel` off `ChatEventBus` (D32, the owner, 2026-10-14). Done, on `main`.
- **Q14.** The naming rule is for new use cases (D35, the owner, 2026-10-15).
- **Q15.** Next: the older observers subscribe before their first read (D36, the owner, 2026-10-15).

## Next

- **D5 is partly done.** Channel and LoRa commands return typed failures. Exceptions still pass through: discovery, verification, the password write and the join in `JoinChannel`'s existing-channel path, and all three LoRa commands (tests pin that those propagate). Turning any into an `Outcome` changes a tested contract: write the mapping and the texts first.
- **No view model imports a bus or a repository** since D32; the Konsist rule in `presentation/viewmodel` holds it.
- **`ChatRepo` follows Bluetooth after any user event** since `43215e8` (from the review of D31 and D32), on local `main`.
- **Every `Observe*` use case on a bus subscribes first** since D36.
- **File names:** `app/model/AppState.kt` holds `UserState` and `ActiveState`; rename it when those move to `user/model`. The rest is in the review's section 6.
- **Unlocked collections** in `ChatRepo`, `LocalChannelPreferences`, `NostrClient` and the Apple Bluetooth services: local work, design first.
- **Open from the plan:** (d) pass-through use cases, (e) scopes and dispatchers, (f) a transport port; where tracked rules live. Plan §3 is a list of old proposals, not instructions (see the note at the top of the plan).

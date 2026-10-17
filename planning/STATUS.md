---
title: Status
tags: [bitchatkmp, planning, status]
---

# Status

Updated 2026-10-16 by local-claude (D36 pushed; D37 decided).

## In flight

| Branch | Holds | State | Waiting on |
|---|---|---|---|
| `main` | `3953e0c`. Since `5bfbd70`: two commits of the owner's side, `43215e8` (`ChatRepo` follows Bluetooth after any user event) and `bbbf892` (a private text no Bluetooth link took is reported as failed), then D36: `98a1d50` (every `Observe*` use case on a bus subscribes before its first read), `3953e0c` (the mining status is kept in memory) | Pushed 2026-10-16 | cloud-claude: start D37 from here |

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

1. **cloud-claude: the next slice is decided (D37): a proposal, no code.** Fetch `main` at `3953e0c`. Read `reference/2026-10-16-local-review-d36.md`, D5, D23 and D24, and the Failures section of `CONVENTIONS.md`. Write `reference/<date>-d5-proposal.md`; put a short plan in `LOG.md` first. No branch.

   **Part 1, commands.** List every use case in `:domain` that can still let an exception through to a view model. Start from the `STATUS.md` Next line (`JoinChannel`'s existing-channel path: discovery, verification, the password write, the join; the three LoRa commands) and the call sites in the view models. Use `git grep` for `try`, `runCatching` and `catch` in `presentation/viewmodel`: they show which calls the view models already guard, and how. For each use case:
   - what can throw, and where (domain, `data:repo`, a transport);
   - what the view model does with it today, and what the user sees (a text, nothing, a crash);
   - the proposal: an `Outcome` with a failure in the feature's sealed failure type, or "stays an exception", with the reason;
   - for a new failure: what it carries, the text the view model shows, and the test that pins it;
   - which existing tests change. Some pin today's propagation; name them.
   
   Cancellation is always rethrown (CONVENTIONS); say so where a `catch` is proposed.

   **Part 2, observers.** What should an `Observe*` use case do when a read fails? Today the flow ends with the exception: on Android and iOS the app ends, and on the desktop and the boards the screen stops following. Give two or three options, for example:
   - end as today;
   - log and keep observing, skipping that read;
   - emit a failure value the view model shows.
   
   For each option give the cost on each platform, what the user sees, and how it fits D4. Recommend one. The owner decides.

   **Part 3:** the order to do it in, as slices that each fit the build you have, and say which need a local build.

## Open questions

None.

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
- **Q15.** Next: the older observers subscribe before their first read (D36, the owner, 2026-10-15). Done, on `main`.
- **Q16.** Next: a written proposal for the rest of D5, observers included (D37, the owner, 2026-10-16).

## Next

- **D5 is partly done.** Channel and LoRa commands return typed failures. Exceptions still pass through: discovery, verification, the password write and the join in `JoinChannel`'s existing-channel path, and all three LoRa commands (tests pin that those propagate). Turning any into an `Outcome` changes a tested contract: write the mapping and the texts first.
- **No view model imports a bus or a repository** since D32; the Konsist rule in `presentation/viewmodel` holds it.
- **`ChatRepo` follows Bluetooth after any user event** since `43215e8` (from the review of D31 and D32), on local `main`.
- **Every `Observe*` use case on a bus subscribes first** since D36.
- **File names:** `app/model/AppState.kt` holds `UserState` and `ActiveState`; rename it when those move to `user/model`. The rest is in the review's section 6.
- **Unlocked collections** in `ChatRepo`, `LocalChannelPreferences`, `NostrClient` and the Apple Bluetooth services: local work, design first.
- **Open from the plan:** (d) pass-through use cases, (e) scopes and dispatchers, (f) a transport port; where tracked rules live. Plan §3 is a list of old proposals, not instructions (see the note at the top of the plan).

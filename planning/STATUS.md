---
title: Status
tags: [bitchatkmp, planning, status]
---

# Status

Updated 2026-10-15 by local-claude (D31 to D34 pushed; D35 and D36 decided).

## In flight

| Branch | Holds | State | Waiting on |
|---|---|---|---|
| `main` | `5bfbd70`. Since `fd93095`: six commits of the owner's side (TUI unread chips, BlueZ connects, relayed-link binding, advertisement registration, geohash presence, BlueZ retry), then this slice: `d7a1aa4` (the wipe publishes `NicknameUpdated` and `FavoritesCleared`), `eaac5ff` (`ObserveActiveChannel`, `ActiveChannel`), `5bfbd70` (`ChatViewModel` uses it; the view-model Konsist rule) | Pushed 2026-10-15 | cloud-claude: start D36 from here |

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

1. **cloud-claude: the next slice is decided (D36).** Fetch `main` at `5bfbd70`. Read `reference/2026-10-15-local-review-d31-d32.md` and the two new lines in `CONVENTIONS.md`: under Architecture (D35) and under Observers and events (the test rule). Put the plan in `LOG.md` first. One branch, inside `:domain`.

   **What.** These `Observe*` use cases on `main` still use `onStart`:
   - `app`: `ObserveAppTheme`, `ObserveBackgroundMode`
   - `chat`: `ObserveChannelMessages`, `ObserveJoinedChannels`, `ObserveJoinedNamedChannels`, `ObserveLatestUnreadPrivatePeer`, `ObserveMeshChannelPeople`, `ObservePrivateChats`, `ObserveSelectedPrivatePeer`, `ObserveUnreadPrivatePeers`
   - `location`: `ObserveCurrentChannelBookmarkState`, `ObserveHasNotes`, `ObserveLocationServicesEnabled`, `ObserveNotes`, `ObservePermissionState`
   - `nostr`: `ObservePowSettings`
   - `tor`: `ObserveTorStatus`
   - `user`: `ObserveUserNickname`

   Those that read in `onStart` and then subscribe to a bus have the window D30 closed: an event published during the first read is lost once another event takes the bus's single replay slot. Give them the shape of `ObserveUserState`: subscribe with an `UNDISPATCHED` launch into a `Channel(BUFFERED)`, then read, then handle the channel.

   **First, in the plan:** a table with one row per observer. Give what it reads, what it subscribes to (a bus, a `StateFlow`, a repository flow, `combine`), whether it has that window, and what you will change. Leave out, and say why:
   - an observer with no window (one that only follows a `StateFlow`, for example);
   - one whose shape is not "read, then re-read on matching events". `ObserveMeshChannelPeople` has its own rules about the first event and about skipped refreshes; read its KDoc and tests before touching it.
   
   Keep each observer's triggers, repeats and replay behaviour exactly; this changes only when it subscribes.

   **Tests:** for each observer changed, the during-the-first-read test in the shape of `ObserveUserStateTest`. It must fail on today's shape; say so per observer.

   **Not in this branch:** any view model, and renaming any use case (D35).

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
- **Q15.** Next: the older observers subscribe before their first read (D36, the owner, 2026-10-15).

## Next

- **D5 is partly done.** Channel and LoRa commands return typed failures. Exceptions still pass through: discovery, verification, the password write and the join in `JoinChannel`'s existing-channel path, and all three LoRa commands (tests pin that those propagate). Turning any into an `Outcome` changes a tested contract: write the mapping and the texts first.
- **No view model imports a bus or a repository** since D32; the Konsist rule in `presentation/viewmodel` holds it.
- **`ChatRepo` stops following Bluetooth after an unrelated user event** (review of D31 and D32, row 2): filed for the owner as its own task.
- **The older observers read before they subscribe:** D36 is on it.
- **File names:** `app/model/AppState.kt` holds `UserState` and `ActiveState`; rename it when those move to `user/model`. The rest is in the review's section 6.
- **Unlocked collections** in `ChatRepo`, `LocalChannelPreferences`, `NostrClient` and the Apple Bluetooth services: local work, design first.
- **Open from the plan:** (d) pass-through use cases, (e) scopes and dispatchers, (f) a transport port; where tracked rules live. Plan §3 is a list of old proposals, not instructions (see the note at the top of the plan).

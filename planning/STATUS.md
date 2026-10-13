---
title: Status
tags: [bitchatkmp, planning, status]
---

# Status

Updated 2026-10-13 by local-claude.

## In flight

| Branch | Holds | State | Waiting on |
|---|---|---|---|
| `main` | `ff4697c`. Since `d1d6d67`: three commits of the owner's side (`0a97ab8` board dialling in the Bluetooth module, `e231574` private text limit for the radio, `068261d` a `GetPrivateTextLimit` use case), then D27: `417f2d1` (error-contract tests) and `ff4697c` (theme observer, and the wipe publishing `ThemeUpdated`) | Pushed 2026-10-13 | cloud-claude: start the next slice from here (D29) |

## D27, as landed

Both branches built and passed as pushed. What changed in review is in `reference/2026-10-13-local-review-d27.md`:

- **A wipe now publishes `ThemeUpdated`,** right after the app store is cleared. It reset the stored theme and told nobody; narrowing `ObserveAppTheme` removed the accident that used to refresh it.
- **Two test changes** in `JoinChannelTest`: the no-commitment case is named as a record of today's behaviour, and it no longer pins the order of the two notifications.
- `GetPrivateTextLimit` (`068261d`) replaced `PrivateTextLimit`. Do not bring the old class or an exception for it back; the Konsist exception list is `AppForegroundState`, `ChatNotices`, `Notice`.

## What was built and run

By local-claude on macOS arm64, on the tree that landed:

- `scripts/verify.sh full`: OK. That is `quick`, `desktop` (with `packageDmg`), `android`, `ios`, `embedded` and `tui` (debug and release links for linuxArm64), `desktop-tui`.
- Module tests, all green: `:domain:jvmTest` (179 tests with the Konsist rules, 2 skipped), `:presentation:viewmodel:jvmTest`, `:data:repo:desktopTest`, `:apps:desktop:test`, and with `-Ptui.enabled=true` the terminal UI tests.
- `scripts/tui-smoke.py`: the desktop terminal UI starts from the real Koin graph and shows its four screens.
- Checked that the tests bite: both wipe tests fail without the publish, and the second fails when it is moved after the barrier; the join test fails when the notifications are published before the join.
- `417f2d1` was not built on its own; the tree at `ff4697c` was.

Not run: any app on a phone or a board.

`claude/error-contract-tests` and `claude/observer-fixes` are deleted; `main` holds them.

## Asks

1. **cloud-claude: the next slice is decided (D28, D29).** Fetch `main` at `ff4697c`; it has three commits you have not seen besides your own. Read `reference/2026-10-13-local-review-d27.md` and the Observers and events section of `CONVENTIONS.md`. Put the plan in `LOG.md` first. Two branches, in this order, both inside `:domain`:
   - **Two Konsist rules in `domain`'s `ArchitectureTest`.** Every interface named `*Repository` sits in `com.bitchat.domain.<feature>.repository`; every interface named `*EventBus` sits in `com.bitchat.domain.<feature>.eventbus`. Select across all production sources of the module, assert the selection is not empty, and plant a misplaced interface to see each rule fail. No exception list is needed today (9 repositories, 7 buses, all in place). Ports that are not repositories or buses (`RequestedTorIntent`, `TorCapability`, `AppInitializer`, …) are not in scope. The other three candidates wait.
   - **File names that differ from their class.** `location/model/ChannelID.kt` to `Channel.kt`; `connectivity/model/BluetoothEvent.kt` to `BluetoothConnectionEvent.kt`; `connectivity/model/LocationEvent.kt` to `LocationConnectionEvent.kt`; `Version` out of `initialization/models/AppInformation.kt` into `Version.kt` in the same package. Same packages, same declarations, no `@Serializable` name changes, nothing outside the renamed files changes. Not the `models` to `model` package move. Use `git mv` so the history follows.

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
- **Q10.** Next: those two rules, then the file names that differ from their class (D29, the owner, 2026-10-13).

## Next

- **D5 is partly done.** Channel and LoRa commands return typed failures. Exceptions still pass through: discovery, verification, the password write and the join in `JoinChannel`'s existing-channel path, and all three LoRa commands (tests pin that those propagate). Turning any into an `Outcome` changes a tested contract: write the mapping and the texts first.
- **Unlocked collections** in `ChatRepo`, `LocalChannelPreferences`, `NostrClient` and the Apple Bluetooth services: local work, design first.
- **Open from the plan:** (d) pass-through use cases, (e) scopes and dispatchers, (f) a transport port; where tracked rules live. Plan §3 is a list of old proposals, not instructions (see the note at the top of the plan).

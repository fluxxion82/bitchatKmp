---
title: Status
tags: [bitchatkmp, planning, status]
---

# Status

Updated 2026-10-13 by cloud-claude (D29 pushed).

## In flight

| Branch | Holds | State | Waiting on |
|---|---|---|---|
| `main` | `ff4697c`. Since `d1d6d67`: three commits of the owner's side (`0a97ab8` board dialling in the Bluetooth module, `e231574` private text limit for the radio, `068261d` a `GetPrivateTextLimit` use case), then D27: `417f2d1` (error-contract tests) and `ff4697c` (theme observer, and the wipe publishing `ThemeUpdated`) | Pushed 2026-10-13 | |
| `claude/konsist-placement-rules` | D28: two Konsist rules in `domain`'s `ArchitectureTest`. One commit on `ff4697c` | Pushed 2026-10-13 at `d79c235` | local agents: build and review |
| `claude/domain-file-names` | D29 part 2: three `git mv` renames and `Version` into `Version.kt`. One commit on `ff4697c` | Pushed 2026-10-13 at `967d59b` | local agents: build and review |

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

1. **Local agents: build and review `claude/konsist-placement-rules` (`d79c235`) and `claude/domain-file-names` (`967d59b`).** Both are branched from `ff4697c` and merge with each other without a conflict. What cloud-claude built and ran is in `LOG.md`.

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

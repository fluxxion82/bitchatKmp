---
title: Status
tags: [bitchatkmp, planning, status]
---

# Status

Updated 2026-10-13 by cloud-claude (D27 pushed).

## In flight

| Branch | Holds | State | Waiting on |
|---|---|---|---|
| `main` | `d1d6d67`. Since `e0bbd4d`: two commits of the owner's (`33542c4` keyboard dismissal in `presentation:design`, `c63c497` Arti cache files and REST client transport retirement), then the second slice: `ab48a4e` (B, Konsist tests), `ec2fbc8` (A, error model), `d1d6d67` (the mesh has its delegate before it starts) | Pushed 2026-10-13 | |
| `claude/error-contract-tests` | D27 part 1: tests only, no file under `commonMain` changes. One commit on `d1d6d67` | Pushed 2026-10-13 at `ff92f7c` | local agents: build and review |
| `claude/observer-fixes` | D27 part 2: `ObserveAppTheme` re-reads only on `ThemeUpdated`, and tests. One commit on `d1d6d67` | Pushed 2026-10-13 at `cb67ad6` | local agents: build and review |

## The second slice, as landed

Both branches built and passed as pushed, on every target. What changed in review, with evidence, is in `reference/2026-10-13-local-review.md`:

- **B.** The Konsist helper reads the parsed supertype instead of matching text, and every rule first checks that it selected something.
- **A.** `LoRaFailure.KeepsOwnRadioSettings(protocol)` names the active radio, decided under the LoRa manager's lock. The failure reasons are nullable and `ChatViewModel` supplies "Unknown error". `ChatRepoLoRaFailureTest` covers which failure the repository picks.
- **Also landed, local work:** `BluetoothMeshAppInitializer` takes the chat repository, so the mesh never runs without its delegate. Before, it announced as "Anonymous" and dropped what it received until the first viewmodel existed.

## What was built and run

By local-claude on macOS arm64, on the tree that landed:

- `scripts/verify.sh full`: OK. That is `quick`, `desktop` (with `packageDmg`), `android`, `ios`, `embedded` and `tui` (debug and release links for linuxArm64), `desktop-tui`.
- Module tests, all green: `:domain:jvmTest` (with its three Konsist rules), `:presentation:viewmodel:jvmTest` (with its one), `:data:repo:desktopTest`, `:data:remote:rest:client:jvmTest`, `:data:remote:transport:lora:jvmTest`, `:data:remote:transport:bluetooth:desktopTest`, `:apps:desktop:test`, and with `-Ptui.enabled=true` the terminal UI tests.
- `scripts/tui-smoke.py`: the desktop terminal UI starts from the real Koin graph and shows its four screens.
- Checked that the tests bite: two planted violations fail the two Konsist rules they break; three mutations of `ChatRepo`'s LoRa failure choice each fail a test; the mesh test failed before its fix.
- `ab48a4e` and `ec2fbc8` were not built on their own; the tree at `d1d6d67` was.

Not run: any app on a phone or a board.

`claude/konsist-architecture-tests` and `claude/error-model` are deleted; `main` holds them.

## Asks

1. **Local agents: build and review `claude/error-contract-tests` (`ff92f7c`) and `claude/observer-fixes` (`cb67ad6`).** Both branch from `d1d6d67` and merge with each other without a conflict. What cloud-claude built and ran is in `LOG.md`. The tests found no defect, so there is no new open question.
2. **Owner:** Q9 when you want more Konsist rules.

## Open questions

- **Q9 (B, `ab48a4e`). Which Konsist rules next?** Candidates in `reference/2026-10-13-local-review.md` §4. Each new rule needs today's exceptions written into the test. Not part of the next slice.

## Answered

- **Q1.** `default` stays on the main queue on iOS for now (D17).
- **Q2.** `GetAppTheme` and `GetUserNickname`; no single read of background mode (D18). On `main`.
- **Q3, Q4.** LoRa hidden where no protocol is registered; an unsupported saved protocol reads as the fallback (D19). On `main`.
- **Q5.** The `ChatRepository` methods only tests called are removed (D20). On `main`.
- **Q6.** B is a Konsist architecture test (D25, the owner, 2026-10-13). On `main`.
- **Q7.** The changed texts are accepted (D26, the owner, 2026-10-13).
- **Q8.** Next: domain tests for the error contract, then the narrow observer fixes (D27, the owner, 2026-10-13).

## Next

- **D5 is partly done.** Channel and LoRa commands return a typed failure. Exceptions from discovery and verification still pass through `JoinChannel` and `SetChannelPassword`, and no other command returns `Outcome`.
- **Unlocked collections** in `ChatRepo`, `LocalChannelPreferences`, `NostrClient` and the Apple Bluetooth services: local work, design first.
- **Open from the plan:** (d) pass-through use cases, (e) scopes and dispatchers, (f) a transport port; the rewrite of plan §3 against D1 to D25; where tracked rules live.

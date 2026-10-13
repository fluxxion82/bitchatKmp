---
title: Status
tags: [bitchatkmp, planning, status]
---

# Status

Updated 2026-10-13 by local-claude.

## In flight

| Branch | Holds | State | Waiting on |
|---|---|---|---|
| `main` | `d1d6d67`. Since `e0bbd4d`: two commits of the owner's (`33542c4` keyboard dismissal in `presentation:design`, `c63c497` Arti cache files and REST client transport retirement), then the second slice: `ab48a4e` (B, Konsist tests), `ec2fbc8` (A, error model), `d1d6d67` (the mesh has its delegate before it starts) | On the owner's machine, not pushed: `origin/main` is still `e0bbd4d` | Owner: push |
| `claude/konsist-architecture-tests`, `claude/error-model` | B and A as pushed (`98b1430`, `951b652`) | Superseded by `main` | Owner: delete |

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

## Asks

1. **Owner:** Q7, Q8 and Q9 below.
2. **cloud-claude:** fetch `main` (it has commits you have not seen) and read `reference/2026-10-13-local-review.md` and the new Failures section of `CONVENTIONS.md`. Wait for the owner's answer to Q8, then propose the slice in `LOG.md` before writing code.

## Open questions

- **Q7 (A, `ec2fbc8`). Are the changed texts right?** Four in chat and six in LoRa settings, all listed in `reference/2026-10-13-local-review.md` §2. local-claude's recommendation: accept; each is more accurate than what it replaces. "This app has no LoRa radio." is shown when the app has no LoRa manager, not when hardware is missing.
- **Q8. What is the next slice?** Recommendation in `reference/2026-10-13-local-review.md` §5: domain tests for the error contract first, then the narrow observer fixes; both stay inside `:domain`.
- **Q9 (B, `ab48a4e`). Which Konsist rules next?** Candidates in `reference/2026-10-13-local-review.md` §4. Each new rule needs today's exceptions written into the test.

## Answered

- **Q1.** `default` stays on the main queue on iOS for now (D17).
- **Q2.** `GetAppTheme` and `GetUserNickname`; no single read of background mode (D18). On `main`.
- **Q3, Q4.** LoRa hidden where no protocol is registered; an unsupported saved protocol reads as the fallback (D19). On `main`.
- **Q5.** The `ChatRepository` methods only tests called are removed (D20). On `main`.
- **Q6.** B is a Konsist architecture test (D25, the owner, 2026-10-13). On `main`.

## Next

- **D5 is partly done.** Channel and LoRa commands return a typed failure. Exceptions from discovery and verification still pass through `JoinChannel` and `SetChannelPassword`, and no other command returns `Outcome`.
- **Unlocked collections** in `ChatRepo`, `LocalChannelPreferences`, `NostrClient` and the Apple Bluetooth services: local work, design first.
- **Open from the plan:** (d) pass-through use cases, (e) scopes and dispatchers, (f) a transport port; the rewrite of plan §3 against D1 to D25; where tracked rules live.

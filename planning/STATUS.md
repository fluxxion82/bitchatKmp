---
title: Status
tags: [bitchatkmp, planning, status]
---

# Status

Updated 2026-10-12 by local-claude.

## In flight

| Branch | Holds | State | Waiting on |
|---|---|---|---|
| `main` | `f13757f`: the first slice (`e4e5d94`) and its follow-up, D18 and D20 (`f13757f`; the owner amended `2e7e6aa` with a one-line edit to `ObserveJoinedChannels`) | Pushed 2026-10-12 | cloud-claude: start the next slice from here |
| `claude/apple-io-off-main` | The Apple dispatcher change (was `b2e6cd5`), with `io` limited to one worker and `IosLocationService` on the main queue; one commit on `main`, on the owner's machine only | Compiles for iOS; not run on a device | Owner: the iPhone test below. Then local agents: locks for the collections listed in `reference/2026-10-12-local-review.md` §1 |

`claude/naming-rule-and-safety-fixes` and `claude/clean-architecture-review-b6fcfl` are deleted (D13).

## What landed, and what changed on the way

The squash holds six of the seven commits: the `Observe` renames, the proof-of-work split with the Nostr bus as a `SharedFlow`, cancellation in `JoinChannel` and `SetChannelPassword`, the iOS Koin list, the registered LoRa protocols, and the dead code. Changed in review (details in `reference/2026-10-12-local-review.md`):

- It did not compile above `:domain`, and the desktop Koin `verify()` failed. Both fixed.
- `ObservePowSettings` took over `main`'s mining status (`d912bd6`).
- Settings hide LoRa where no protocol is registered (Q3) and a saved protocol the app cannot run reads as BitChat (Q4).
- Comments trimmed. The long commit messages and their trailers went with the squash.
- The Apple dispatcher commit was taken out and has its own branch.

## What was built and run

On the merged tree, by local-claude, macOS arm64:

- `scripts/verify.sh full`: OK. That is `quick`, `desktop` (with `packageDmg`), `android` (the APK carries the Arti library), `ios` (three `:iosdi` frameworks and the Apple tests), `embedded` and `tui` (debug and release links for linuxArm64), `desktop-tui`.
- Module tests, all green: `:domain:jvmTest`, `:presentation:viewmodel:jvmTest` (68), `:presentation:viewvo:jvmTest`, `:data:repo:desktopTest` (309), the three LoRa modules' `jvmTest`, `:data:remote:tor:jvmTest` and `macosArm64Test`, `:apps:desktop:test`, `:iosdi:iosSimulatorArm64Test`, and with `-Ptui.enabled=true` `:presentation:tui:jvmTest` (469), `:presentation:tui:binding:jvmTest`, `:apps:desktop:tui:test`.
- New tests: `InMemoryNostrEventBusTest`, `LoRaSettingsRepoTest`, `DesktopModulesTest.desktopOffersOnlyTheLoRaProtocolsItRegisters`, one case in `SettingsViewModelLoRaTest`.

The follow-up (built at `2e7e6aa`, before the owner's one-line amend): the same module tests plus `:data:remote:transport:bluetooth:desktopTest`, the desktop terminal UI tests, `:apps:droid:assembleDebug`, the iOS simulator framework link and the linuxArm64 compiles of both embedded apps, all green; Codex "nothing blocking" in its second round.

Not run: any app on a device or simulator.

## Asks

1. **Owner: iPhone test of `claude/apple-io-off-main`.** Cold start; mesh chat with another device, both ways; a private chat; a geohash channel and its notes; **the location channel list and "locate me", and granting or revoking location permission while the app runs** (the location service changed); Settings with Tor on and off and proof of work; background and back; then leave it running. Watch for crashes and for UI that stops updating.
2. **cloud-claude: read `CONVENTIONS.md`** (new: the rules you could not see) **and `reference/2026-10-12-local-review.md`,** then propose the next slice in `LOG.md` before writing code. Start from `main`.

## Open questions

None.

## Answered

By the owner, 2026-10-12. Evidence for each: `reference/2026-10-12-local-review.md` §3.

- **Q1. Should `default` also leave the main queue on iOS?** Not yet (D17).
- **Q2. One-shot `Get` use cases for theme, background mode and nickname?** `GetAppTheme` and `GetUserNickname` yes; background mode no, Settings drops its single read (D18). On `main`. The background switch now appears with its first observed value instead of before it.
- **Q3. Hide the LoRa card when no protocol is available?** Yes; on `main` (D19).
- **Q4. Reset a saved MeshCore choice on desktop or Android?** Yes, on read; on `main` (D19).
- **Q5. Remove the `ChatRepository` methods only tests call now?** Yes, now, by a local agent (D20). On `main`.

## Next

- **Decisions still open from the plan:** (d) pass-through use cases, (e) where scopes and dispatchers live (the plan's recommendation rests on a wrong premise: the tests do inject dispatchers), (f) a transport port.
- **Error model (D5):** wants its own small slice. `Outcome` has one type parameter today.
- **Guardrails (plan §3):** rewrite the list against D1 to D16 first; it still requires `FlowUsecase`.
- **Unsynchronised collections** in `ChatRepo`, `LocalChannelPreferences` and `NostrClient`: on every platform today, found while reading the dispatcher change. Local work, with a design first.
- **Where tracked rules live,** now that `docs/` is out of the repository and `CLAUDE.md` is local: the owner's call. Until then, `CONVENTIONS.md`.

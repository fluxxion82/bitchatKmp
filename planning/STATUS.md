---
title: Status
tags: [bitchatkmp, planning, status]
---

# Status

Updated 2026-10-12 by local-claude.

## In flight

| Branch | Holds | State | Waiting on |
|---|---|---|---|
| `main` | The first slice, squashed to one commit, `e4e5d94`, on top of four commits the cloud session had not seen (`06178a4`, `132a910`, `d912bd6`, `3c46c5c`) | Merged on the owner's machine, all gates green. Not pushed: `origin/main` is still `389fe0a` | Owner: push `main` |
| `claude/apple-io-off-main` | The Apple dispatcher change (was `b2e6cd5`), with `io` limited to one worker and `IosLocationService` on the main queue; one commit on `main`, on the owner's machine only | Compiles for iOS; not run on a device | Owner: the iPhone test below. Then local agents: locks for the collections listed in `reference/2026-10-12-local-review.md` §1 |
| `claude/naming-rule-and-safety-fixes` | The first slice as pushed, `23dad85` | Superseded by `main` | Owner: delete |
| `claude/clean-architecture-review-b6fcfl` | The first copy of the review docs | To be deleted (D13) | Owner: delete |

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

Not run: any app on a device or simulator.

## Asks

1. **Owner: iPhone test of `claude/apple-io-off-main`.** Cold start; mesh chat with another device, both ways; a private chat; a geohash channel and its notes; **the location channel list and "locate me"** (the location service changed); Settings with Tor on and off and proof of work; background and back; then leave it running. Watch for crashes and for UI that stops updating.
2. **Owner: answers to Q1, Q2 and Q5,** and a word on Q3 and Q4, which are implemented.
3. **cloud-claude: read `CONVENTIONS.md`** (new: the rules you could not see) **and `reference/2026-10-12-local-review.md`,** then propose the next slice in `LOG.md` before writing code. Start from `main` once this file says it is pushed.

## Open questions

Each with local-claude's recommendation; evidence in `reference/2026-10-12-local-review.md` §3. They move to Answered when the owner confirms.

- **Q1. Should `default` also leave the main queue on iOS?** Recommendation: not yet. `IosAppLifecycleObserver.initialize` checks for the main thread and runs on `default`, so the app would stop at launch. The Apple Bluetooth services' state needs one owner each first; it is already touched from CoreBluetooth's own queues.
- **Q2. One-shot `Get` use cases for theme, background mode and nickname?** Recommendation: `GetAppTheme` and `GetUserNickname` yes. Background mode no: Settings reads it once and then collects the same flow, so drop the single read.
- **Q3. Hide the LoRa card when no protocol is available?** Recommendation: yes. Implemented: `loraAvailable = loraProtocols.isNotEmpty()`.
- **Q4. Reset a saved MeshCore choice on desktop or Android?** Recommendation: yes, on read. Implemented in `LoRaSettingsRepo.getLoRaSettings()`; the preference itself is not rewritten.
- **Q5. Remove the `ChatRepository` methods only tests call now?** Recommendation: now, by a local agent. They are `onPeersUpdated`, `sendGeohashMessage(content, geohash, nickname)`, `sendMeshMessage(content, nickname)` and `BluetoothMeshDelegate.decryptChannelMessage`. Their callers are security tests the cloud session cannot compile.

## Answered

None yet.

## Next

- **Decisions still open from the plan:** (d) pass-through use cases, (e) where scopes and dispatchers live (the plan's recommendation rests on a wrong premise: the tests do inject dispatchers), (f) a transport port.
- **Error model (D5):** wants its own small slice. `Outcome` has one type parameter today.
- **Guardrails (plan §3):** rewrite the list against D1 to D16 first; it still requires `FlowUsecase`.
- **Unsynchronised collections** in `ChatRepo`, `LocalChannelPreferences` and `NostrClient`: on every platform today, found while reading the dispatcher change. Local work, with a design first.
- **Where tracked rules live,** now that `docs/` is out of the repository and `CLAUDE.md` is local: the owner's call. Until then, `CONVENTIONS.md`.

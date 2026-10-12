---
title: Status
tags: [bitchatkmp, planning, status]
---

# Status

Updated 2026-10-12 by cloud-claude.

## In flight

| Branch | Holds | State | Waiting on |
|---|---|---|---|
| `claude/naming-rule-and-safety-fixes` | Seven commits on `main` at `389fe0a` (table below) | Pushed, not reviewed | Local agents: build, review, device tests |
| `claude/clean-architecture-review-b6fcfl` | The first copy of the review docs. Its pull request, fluxxion82/bitchatKmp#1, is closed | To be deleted (D13) | Owner: delete it; cloud-claude's GitHub access cannot delete branches |

Commits on `claude/naming-rule-and-safety-fixes`, oldest first:

| Commit | What | Question |
|---|---|---|
| `a2b69b1` | `Get*` use cases that return a flow renamed `Observe*`; `ObserveMeshChannelPeople` implements `Usecase` | Q2 |
| `f0373f6` | `ObservePowSettings` plus a one-shot `GetPowSettings`; `SetPowSettings` implements `Usecase`; the Nostr bus is a `SharedFlow` like the others | |
| `b2e6cd5` | Apple dispatcher facade: `io` off the main queue, `default` kept on it | Q1 |
| `9b5610f` | `JoinChannel` and `SetChannelPassword` rethrow cancellation | |
| `6dd64e9` | The iOS Koin module list without repeats | |
| `af2ca13` | Settings offer only the LoRa protocols an app registers (the MeshCore crash) | Q3, Q4 |
| `23dad85` | Dead code deleted | Q5 |

## What cloud-claude could and could not verify

- **Verified:** `:domain` and its tests compile at every commit, and its `jvmTest` passes at the tip (147 tests, 2 skipped, 0 failures). This ran in a stand-alone build that leaves out the Android Gradle Plugin.
- **Not compiled:** every module that applies the Android Gradle Plugin (all of `data:*`, `presentation:*`, `apps:*`), `iosdi`, and every Apple and Linux native target. Google Maven is blocked in the cloud environment and the host is Linux.
- **Not run:** any app or device.

## Asks for the local agents

1. **Build and test.** At least `./gradlew :domain:jvmTest :presentation:viewmodel:jvmTest :data:repo:desktopTest :data:remote:transport:lora:jvmTest`, then `-Ptui.enabled=true :presentation:tui:jvmTest`, the desktop and Android apps, and the Apple test sources the branch touches (`data/remote/tor` appleTest, `iosdi` iosTest). Fix what breaks on the same branch.
2. **iOS device test of `b2e6cd5`, which the owner asked for right away.** Cold start; mesh chat with another device, both ways; a private chat; a geohash channel and its notes; Settings with Tor on and off and proof of work; background and back; then leave it running. Watch for crashes, Kotlin/Native concurrent-modification errors, and UI that stops updating.
3. **Desktop and Android, `af2ca13`.** Settings → LoRa → Protocol lists BitChat and Meshtastic only, switching between them works, and nothing crashes. The embedded apps still list MeshCore.
4. **Review with Codex** against `DECISIONS.md`. Push changes to the same branch and add a `LOG.md` entry saying what you changed.

## Open questions

- **Q1 (`b2e6cd5`).** The owner chose to move all four Apple dispatcher contexts off the main queue. The commit moves only `io`. The Apple Bluetooth services launch characteristic writes, notifies and connection callbacks on the application scope, whose context is `default`, and on `Dispatchers.Default` those would run in parallel against the same peripherals. Move `default` too once `io` has been tried on a device?
- **Q2 (`a2b69b1`).** Settings reads `observeAppTheme()` and `observeBackgroundMode()` once with `firstOrNull()`. `DmViewModel` and `LocationNotesViewModel` read `observeUserNickname(Unit).first()` once. Proof of work got a one-shot `GetPowSettings` for the same case. Add one-shot `GetAppTheme`, `GetBackgroundMode` and `GetUserNickname`?
- **Q3 (`af2ca13`).** `SettingsViewModel` reports LoRa available whenever `GetLoRaSettings` is injected, which is always. iOS registers no protocol, so its LoRa card now lists none. Hide the LoRa card when no protocol is available?
- **Q4 (`af2ca13`).** Someone on desktop or Android who picked MeshCore before this still has it saved. It shows as current with no matching option, and the radio stays on BitChat. Reset a saved protocol the app cannot run to BitChat when it is read?
- **Q5 (`23dad85`).** `ChatRepository.onPeersUpdated` and the short `sendGeohashMessage` and `sendMeshMessage` overloads have no production caller, but three `data:repo` tests call them. `BluetoothMeshDelegate.decryptChannelMessage` is never invoked. Remove them now, reworking those tests, or with the `ChatRepository` split later?

## Answered

None yet.

## Next, once the branch lands

- **Decisions still open from the plan:** (d) whether one-line pass-through use cases stay the convention (D2, "every use case implements `Usecase`", points to yes), (e) where coroutine scopes and dispatchers live, (f) a transport port.
- **Error model (D5):** `Outcome` with a sealed failure per feature in the use cases that can fail. The LoRa ones return `Boolean` today.
- **Guardrails** from the plan's §3: a CI test job, written rules in a root `CLAUDE.md`, a Gradle layering check. Not started.

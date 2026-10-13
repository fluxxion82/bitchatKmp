---
title: Local review of the second slice (Konsist tests, error model)
date: 2026-10-13
tags: [bitchatkmp, architecture, review, local]
related:
  - "[[2026-10-12-local-review]]"
  - "[[2026-10-12-clean-architecture-plan]]"
---

# Local review of the second slice

- **Who:** local-claude, with Codex (`gpt-6-astra`, high effort): one review of the code, one of the documents on `planning` at `cbec1c2`, and a second round on the fixed code. local-claude built every target and checked each finding it took.
- **What:** B, `claude/konsist-architecture-tests` at `98b1430`, and A, `claude/error-model` at `951b652`, merged onto the owner's `main`, which had two commits the cloud session had not seen (`33542c4` keyboard dismissal in `presentation:design`, `c63c497` Arti cache files and REST client transport retirement in `data:remote`).
- **Result:** both branches built and passed as pushed, on every target. That is the first time. Three text findings and two test-robustness findings were fixed before landing.

## 1. What was found

| # | Found by | What | Done |
|---|---|---|---|
| 1 | Codex | **The radio-settings failure could name the wrong protocol.** `SettingsViewModel` put the *selected* protocol into "… keeps its own radio settings". A switch that fails leaves the old radio active while Settings keeps the new selection, so a region change on a board still running MeshCore said "BitChat keeps its own radio settings" | `LoRaFailure.KeepsOwnRadioSettings(protocol)` carries the active protocol and the text names that one. Round 2 then found the repository reading the manager's active protocol and capability after the manager had let go of its lock, where a concurrent switch could change both: `LoRaProtocolManager.reconfigure` now returns a `Reconfiguration` (`Applied`, `NotApplied`, `KeptOwnSettings(protocol)`) decided under its own lock, and `ChatRepo` only maps it. Tests in the manager, the repository and the viewmodel |
| 2 | Codex | **"Unknown error" was a UI text in the domain** (`e.message ?: "Unknown error"` in `JoinChannel` and `SetChannelPassword`), and it changed the password texts as well as the create text the documents named | `CreationFailed.reason` and `PasswordNotSaved.reason` are nullable; `ChatViewModel` supplies "Unknown error" (D24) |
| 3 | Codex | **An unstated text change:** an exception out of a region or power change, with a daemon protocol selected, used to say "<protocol> keeps its own radio settings"; it now says the generic "saved but not applied" | Kept: an exception says nothing about which radio is active. Listed below |
| 4 | Codex, cloud-claude | Nothing tested which failure `ChatRepo` picks | `ChatRepoLoRaFailureTest`: `NoRadio` for no radio and for a radio that is not the manager; `NotRegistered` with a running radio left alone; `NotApplied` for a failed start and a failed reconfigure; `KeepsOwnRadioSettings` naming an active Meshtastic. Three mutations of `ChatRepo` each fail one of them |
| 5 | Codex | **The Konsist helper matched text.** A class that only mentions `Usecase<…>` in its constructor passed the feature-package rule, and could confuse the `Observe` rule | The helper reads the parsed supertype. A planted `class GetBogus : Usecase<Unit, Flow<Int>>` and `class Helper(val dependency: Usecase<Unit, Unit>)` now fail the two rules |
| 6 | Codex | **Konsist's assertions pass on an empty list.** A scope that matched nothing would have passed all three domain rules | Each rule first asserts that it selected something |

Read and found fine: no caller is left on the old `Boolean` or `String` signatures in any source set, Swift included. No `when` over the changed failures is broken. `BaseApiClient` still puts the status and body into `Failure.ServerError`; the dropped messages were labels nobody read. A refused switch never touched the manager's state on `main` either. Konsist finds the module from a worktree and from either module's test task, and looks at `commonMain`, `appleMain`, `jvmMain` and `linuxMain`. The four commit messages are one line each with no trailer.

## 2. Every text that changed

Chat (`m` is the exception's message):

| Path | Before | Now |
|---|---|---|
| create throws without a message | `Failed to create channel: null` | `Failed to create channel: Unknown error` |
| `/pass <new>` on a channel without a password throws without a message | `Failed to set password: null` | `Failed to set password: Unknown error` |
| `/pass <current> <new>` on a channel without a password throws | `Failed to set password: m` | `Failed to change password: m` |
| a password change throws without a message | `Failed to change password: null` | `Failed to change password: Unknown error` |

Everything else in chat reads as before, including "wrong password", "incorrect password", "Failed to join channel" and "only the creator can set the initial password".

LoRa settings. S = "Could not switch to P. Check the radio service logs, then retry." R = "Radio preference saved but not applied. Check the radio service logs, then retry." K(P) = "Radio preference saved but not applied. P keeps its own radio settings; configure those separately."

| Path | Before | Now |
|---|---|---|
| switch, the app has no LoRa manager | S | "This app has no LoRa radio." |
| switch to a protocol the app did not register | S | "P is not available in this app." |
| region or power, no LoRa manager | R, or K(selected) | "This app has no LoRa radio." |
| region or power, the active radio keeps its own settings | K(selected) when the selection was not BitChat, else R | K(active) |
| region or power fails on a radio that takes settings, daemon protocol selected | K(selected) | R |
| region or power throws, daemon protocol selected | K(selected) | R |

`NoRadio` means the app has no `LoRaProtocolManager`. It does not detect missing hardware.

What `ChatRepo` returns:

| Call | Condition | Result |
|---|---|---|
| both | `lora` is not a `LoRaProtocolManager` | `NoRadio` |
| switch | the type is not in `availableTypes` | `NotRegistered`; the manager is not called |
| switch | the manager returns true / false | success / `NotApplied` |
| reconfigure | the manager answers `Applied` | success |
| reconfigure | `KeptOwnSettings(protocol)`: the active radio does not take settings | `KeepsOwnRadioSettings(protocol)` |
| reconfigure | `NotApplied` | `NotApplied` |

The three use cases save the preference first and pass the result on. An exception is not turned into a failure.

## 3. Corrections to the documents

- **`STATUS.md`, Proposal, "the text the chat showed before, with one exception".** Four texts changed, in the first table above.
- **"Its two existing texts stay."** There were three (S, R, K), and K changed which protocol it names.
- **"`PasswordNotSaved(reason)` (storing the password threw)."** Its `catch` also covers the event published after the write, as `CreationFailed` did on `main`.
- **"Gone: … the manager's 'not registered' line."** The line is still in `LoRaProtocolManager`; a switch refused by `ChatRepo` no longer reaches it.
- **B's import rule** checks `import` lines. It does not see a Gradle dependency or a fully qualified name, so "`domain` depends on no project module" is still held only by `domain/build.gradle.kts`.
- **D5 is not finished.** This slice changes how a failure is represented. `JoinChannel` and `SetChannelPassword` still let exceptions from discovery and verification through, and nothing but the LoRa and channel commands returns `Outcome`.
- **The scratch builds.** The LOG gives counts for a scratch merge of A and B and for a viewmodel build with AndroidX stubbed, without the tree or the commands. Next time name both input commits and the tasks, and do not call a stubbed build `:presentation:viewmodel:jvmTest`.

## 4. What Konsist does and does not hold

Held now: `Observe*` exactly when the result is a `Flow`; a public class directly in a feature package is a use case (three named exceptions); the domain's imports; no viewmodel function named like a constructor parameter.

Cheap to add to the same tests, each with today's exceptions written down: viewmodels import no `*.repository.*`; repository interfaces sit under `domain/<feature>/repository`; event-bus interfaces under `domain/<feature>/eventbus`; the parent modules `:apps:desktop` and `:apps:embedded` import no UI toolkit; no comma in a backticked test name under `commonTest`.

Not for Konsist: Gradle dependency direction, Koin resolution, lock order, bounds, single writers, cancellation, wire and stored formats, Tor fail-closed. Those are for a Gradle check, Koin `verify()` and behaviour tests. Rules the owner rejected or has not decided stay out: `FlowUsecase`, a `domainModule` of factories only, a ban on `catch (e: Exception)`, removing the buses.

Konsist 0.18.1 parses with Kotlin 2.4.21 while the project is on 2.5.0-Beta1. Nothing in the domain fails to parse today; a new language feature in the domain could.

## 5. The next slice

Recommendation, for the owner to choose from. The cloud session verifies only `:domain`, so the order puts domain-only work first.

1. **Domain tests for the error contract (cloud).** `SetChannelPasswordTest` (new): wrong password, not the creator, a channel with no commitment, a write that throws with and without a message, cancellation. `JoinChannelTest`: protected channel without a password, a mismatched commitment, a channel with no advertised commitment. `SwitchLoRaProtocolTest` and `ReconfigureLoRaSettingsTest`: each `LoRaFailure` passed on, the preference saved before the call. No contract changes.
2. **Narrow observer fixes with tests (cloud).** `ObserveAppTheme` re-reads on every `AppEvent`; filter `ThemeUpdated`. Test the start of `ObserveJoinedChannels` and `ObserveUserNickname` when an unrelated event holds the replay slot. Keep D4: no shared helper, no repository flows.
3. **More Konsist rules (cloud),** from section 4, after the owner has seen the list.
4. **File names that differ from their class (cloud, domain only):** `ChannelID.kt`, `BluetoothEvent.kt`, `LocationEvent.kt`, `Version` in its own file. Same packages, no import changes outside the file.
5. **Local, not cloud:** anything that reaches a viewmodel or `data:*` (`ObserveUserState`, `ObserveActiveChannel`, the `Request` to `Params` renames, model moves), the unlocked collections, the Apple Bluetooth services.
6. **A written design first, reviewed locally:** notices and command execution with the epoch, scopes and dispatchers, `AppLifecycle`, any part of the `ChatRepo` split.

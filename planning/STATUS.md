---
title: Status
tags: [bitchatkmp, planning, status]
---

# Status

Updated 2026-10-13 by cloud-claude (A pushed).

## In flight

| Branch | Holds | State | Waiting on |
|---|---|---|---|
| `main` | `e0bbd4d`: the first slice (`e4e5d94`), its follow-up D18 and D20 (`f13757f`), and the Apple dispatcher change (`e0bbd4d`) | Pushed 2026-10-12 | |
| `claude/konsist-architecture-tests` | B: Konsist rules, two viewmodel renames. Two commits on `e0bbd4d` | Pushed 2026-10-13 at `98b1430` | local agents: build and review |
| `claude/error-model` | A: `Outcome.Error` without a message, `LoRaFailure`. Two commits on `e0bbd4d` | Pushed 2026-10-13 at `951b652` | local agents: build and review |

`claude/naming-rule-and-safety-fixes` and `claude/clean-architecture-review-b6fcfl` are deleted (D13).

## What landed, and what changed on the way

The squash holds six of the seven commits: the `Observe` renames, the proof-of-work split with the Nostr bus as a `SharedFlow`, cancellation in `JoinChannel` and `SetChannelPassword`, the iOS Koin list, the registered LoRa protocols, and the dead code. Changed in review (details in `reference/2026-10-12-local-review.md`):

- It did not compile above `:domain`, and the desktop Koin `verify()` failed. Both fixed.
- `ObservePowSettings` took over `main`'s mining status (`d912bd6`).
- Settings hide LoRa where no protocol is registered (Q3) and a saved protocol the app cannot run reads as BitChat (Q4).
- Comments trimmed. The long commit messages and their trailers went with the squash.
- The Apple dispatcher commit was taken out, reworked (`io` limited to one worker, `IosLocationService` on the main queue), run by the owner on an iPhone, and then landed as `e0bbd4d` (D21).

## What was built and run

On the merged tree, by local-claude, macOS arm64:

- `scripts/verify.sh full`: OK. That is `quick`, `desktop` (with `packageDmg`), `android` (the APK carries the Arti library), `ios` (three `:iosdi` frameworks and the Apple tests), `embedded` and `tui` (debug and release links for linuxArm64), `desktop-tui`.
- Module tests, all green: `:domain:jvmTest`, `:presentation:viewmodel:jvmTest` (68), `:presentation:viewvo:jvmTest`, `:data:repo:desktopTest` (309), the three LoRa modules' `jvmTest`, `:data:remote:tor:jvmTest` and `macosArm64Test`, `:apps:desktop:test`, `:iosdi:iosSimulatorArm64Test`, and with `-Ptui.enabled=true` `:presentation:tui:jvmTest` (469), `:presentation:tui:binding:jvmTest`, `:apps:desktop:tui:test`.
- New tests: `InMemoryNostrEventBusTest`, `LoRaSettingsRepoTest`, `DesktopModulesTest.desktopOffersOnlyTheLoRaProtocolsItRegisters`, one case in `SettingsViewModelLoRaTest`.

The follow-up (built at `2e7e6aa`, before the owner's one-line amend): the same module tests plus `:data:remote:transport:bluetooth:desktopTest`, the desktop terminal UI tests, `:apps:droid:assembleDebug`, the iOS simulator framework link and the linuxArm64 compiles of both embedded apps, all green; Codex "nothing blocking" in its second round.

On a device: the owner ran `e0bbd4d` on an iPhone 13 (location, mesh chat with a board, Tor, proof of work, Settings, background and back, a soak): no error or crash seen. Not run by an agent: any app on a device or simulator.

## Proposal

B, then A (D22).

**B is pushed:** `claude/konsist-architecture-tests` at `98b1430`, two commits on `main` (`e0bbd4d`). Konsist 0.18.1 as a test dependency of `:domain` and `:presentation:viewmodel`.
- `domain/.../ArchitectureTest.kt`: a use case is named `Observe*` exactly when its `Usecase<P, R>` has a `Flow` for R; every public class in a feature package is a use case, except `AppForegroundState`, `ChatNotices` and `Notice`; production code imports only `com.bitchat.domain`, `kotlin`, `kotlinx` and `org.koin`.
- `presentation/viewmodel/.../ArchitectureTest.kt`: no viewmodel function has the name of a constructor parameter. It found two on `main`, both working only because the call passes an argument: `ChatViewModel.sendMessage()` and `LocationChannelsViewModel.endGeohashSampling()`. The injected use cases are now `sendMessageUseCase` and `endGeohashSamplingUseCase`, as `MainViewModel` already does with `toggleFavoriteUseCase`.

**A is pushed:** `claude/error-model` at `951b652`, two commits on `main` (`e0bbd4d`). It merges with B without a conflict.
- `Outcome.Error(cause: Failure)`. `JoinChannel`, `SetChannelPassword` and `BaseApiClient` build it without a message.
- `ChannelFailure` changes. It gains `JoinFailed` (the repository refused the join) and `PasswordNotSaved(reason)` (storing the password threw), so the view model can tell apart the cases that all shared `CreationFailed` and a message. It loses `NotOwner`, `ChannelNotFound` and `AlreadyJoined`, which nothing built (D10).
- `ChatViewModel` maps each failure to the text the chat showed before, with one exception: `/pass <current> <new>` on a channel that has no password yet now says "Failed to change password" rather than "Failed to set password". It uses the same test as the success text, which already says "password changed" there. A create that threw without a message now reads "Unknown error" instead of "null".
- `LoRaFailure` (`domain/lora/model/failure`): `NoRadio`, `NotRegistered`, `KeepsOwnRadioSettings`, `NotApplied`. `ChatRepository.switchLoRaProtocol(LoRaProtocolType)` and `reconfigureLoRa(...)` return `Outcome<Unit>`. `ChatRepo` picks the failure. A protocol outside `availableTypes` is `NotRegistered`, and the manager is not called. A `reconfigure` that returns false is `KeepsOwnRadioSettings` when the active protocol does not configure its radio, and `NotApplied` otherwise. The three use cases return `Outcome<Unit>`.
- `SettingsViewModel` maps the failure, no longer the selected protocol, to text. Its two existing texts stay. New: "This app has no LoRa radio." (`NoRadio`) and "<protocol> is not available in this app." (`NotRegistered`). A region or power change now says "keeps its own radio settings" when the active radio keeps them. Before, it said so whenever the selected protocol was not BitChat.
- Gone: `ChatRepo`'s two "LoRaProtocolManager not available" log lines, and the manager's "not registered" line for a switch, since `ChatRepo` checks before calling it. The failure now reaches the screen instead.
- Tests: one new in `JoinChannelTest` (`JoinFailed`) and one in `SettingsViewModelLoRaTest` (`NotRegistered`). The LoRa tests in domain and the viewmodel, and `LoRaSavedConfigTest`, now expect `Outcome`. Nothing tests `ChatRepo` picking `NotRegistered` or `KeepsOwnRadioSettings`.

## Asks

1. **Local agents: build and review B (`98b1430`) and A (`951b652`).** What cloud-claude built is in `LOG.md`. `ChatRepo`, `BaseApiClient` and `LoRaSavedConfigTest` were not built anywhere. A `ChatRepo` test for `NotRegistered` and `KeepsOwnRadioSettings` would cover what the scratch builds could not.
2. **Owner:** the two new LoRa texts and the `/pass` wording above, if you want them different.

## Open questions

None.

## Answered

- **Q6. What does B hold?** A Konsist architecture test (D25). On `claude/konsist-architecture-tests`.
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

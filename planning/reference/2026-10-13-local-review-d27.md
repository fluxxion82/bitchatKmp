---
title: Local review of the third slice (D27: error-contract tests, observer fixes)
date: 2026-10-13
tags: [bitchatkmp, architecture, review, local]
related:
  - "[[2026-10-13-local-review]]"
---

# Local review of the third slice (D27)

- **Who:** local-claude, with Codex (`gpt-6-astra`, high effort): one review of the code, one of the documents on `planning` at `5af8a5b`, and a second round on the fixed code. local-claude built every target.
- **What:** `claude/error-contract-tests` at `ff92f7c` and `claude/observer-fixes` at `cb67ad6`, merged onto the owner's `main`, which had three commits the cloud session had not seen (`0a97ab8` board dialling, `e231574` private text limit for the radio, `068261d` a `GetPrivateTextLimit` use case).
- **Result:** both branches built and passed as pushed. The tests are good: Codex went through all 24 new or changed test methods and found each fails for the rule it names. One real defect came out of the observer change, and it was older than the change.

## 1. What was found

| # | Found by | What | Done |
|---|---|---|---|
| 1 | local-claude, Codex | **A wipe reset the stored theme and told nobody.** `ClearAllData` calls `AppRepo.clearData()`, which writes `AppTheme.SYSTEM`; nothing published `ThemeUpdated`. The screen kept the old theme until something else published an `AppEvent` (a background-mode toggle) or the app restarted. With `ObserveAppTheme` filtered to `ThemeUpdated`, that accidental refresh was gone too, so the theme stayed stale for good | `ClearAllData` takes the `AppEventBus` and publishes `ThemeUpdated` right after the app store is cleared, inside the wipe's barrier. It took three shapes: after the block (a store failing later skipped it), in a `finally` (it could replace that store's exception with a cancellation), and this one, which is one statement and leaves every other path as it was. Two tests run a real collector across a wipe, one with a store that throws; both fail without the statement. Accepted: the publish waits for the app bus's collectors, like every publish on every bus (D4), now inside the barrier |
| 2 | Codex | The test for a protected channel that advertises no commitment read like a rule ("takes any password") | Renamed `a password is not verified today when the channel advertises no commitment`, with a line saying it records today's behaviour |
| 3 | Codex | The same test pinned the order of `ChannelJoined` and `ChannelListUpdated`; the create path publishes them the other way round | It checks write, join, `ChannelJoined` in order and join before `ChannelListUpdated`, without ordering the two notifications. A mutation that publishes before joining fails it |

The lesson of row 1, now in `CONVENTIONS.md`: before an observer is narrowed to one event, find every writer of what it observes and check that each publishes that event. `SetAppTheme` was the only *publisher* of `ThemeUpdated`; it was not the only *writer* of the theme.

Read and found fine: `ObserveAppTheme.kt` is the only production file the branches change. `MainViewModel` is its only collector; the Android, desktop, embedded and terminal apps all show that one state. The observer tests' `expectNoEvents()` is not a barrier in general, but it is deterministic here: Turbine collects unconfined and the stubs do not suspend, so a wrong second read would already be there. No test asserts its own stub; the loops build fresh mocks each time. The 29 mutations in the LOG are all plausible against the tests as written. `GetPrivateTextLimit` from `068261d` passes the Konsist rules; the exception list is still the three names.

## 2. Corrections to the documents

- **`LOG.md`, "No test showed a defect."** The error-contract tests found none. The observer tests reproduced the theme defect they were written for, and the review found the wipe behind it.
- **`LOG.md`, "`SetAppTheme` is the only publisher of `ThemeUpdated`, and it still re-reads."** `SetAppTheme` writes and publishes; `ObserveAppTheme` re-reads. And the wipe now publishes it too.
- **`STATUS.md`, Next, D5.** Exceptions still pass through more than discovery and verification: the password write and the join in `JoinChannel`'s existing-channel path, and all three LoRa commands. The new tests pin that the LoRa ones propagate. Turning any of them into an `Outcome` changes a tested contract and needs the mapping and the texts written down first.
- **The plan's status notes** stop at the first slice. A note is added: Apple `io` landed (D21), D5 is partly done, D23 and D24 govern `Outcome`, and plan §3's `FlowUsecase`, factory-only `domainModule`, blanket exception ban, repository flows and `:apps:common` are old proposals, not instructions.

## 3. Q9: the five Konsist candidates today

Codex checked each against the code. None has a violation today, so none needs an exception list.

| Rule | Where it would live | Violations today |
|---|---|---|
| Repository interfaces sit under `domain/<feature>/repository` | `domain` `ArchitectureTest` | none (9 interfaces) |
| Event-bus interfaces sit under `domain/<feature>/eventbus` | `domain` `ArchitectureTest` | none (7 interfaces) |
| Viewmodel sources import no `*.repository.*` | `presentation/viewmodel` `ArchitectureTest` | none. Not to be widened to buses without a decision: `MainViewModel` imports `UserEventBus` and `ChatViewModel` imports `ChatEventBus` |
| `:apps:desktop` and `:apps:embedded` (the parents, not the apps under them) import no UI toolkit | a test in `:apps:desktop` | none |
| No comma in a backticked test name under `commonTest` | wherever a repository-wide source check can live | none in 47 files. JVM tests may keep theirs |

The first two are inside `:domain`, so the cloud session can verify them. The other three read other modules; the cloud session can run them as source checks but cannot run those modules' own test tasks.

## 4. The next slices

Recommendation, in order. The owner chooses.

1. **Konsist: the two domain placement rules (cloud),** if Q9 says yes. Assert a non-empty selection; check a planted misplaced interface fails each.
2. **File names that differ from their class (cloud, domain only).** `location/model/ChannelID.kt` to `Channel.kt`, `connectivity/model/BluetoothEvent.kt` to `BluetoothConnectionEvent.kt`, `connectivity/model/LocationEvent.kt` to `LocationConnectionEvent.kt`, `Version` out of `AppInformation.kt` into its own file. Same packages and declarations; no import changes outside the file. Not the `models` to `model` package move.
3. **One viewmodel's observation behind use cases (local).** `ObserveUserState` and `ObserveFavorites`, replacing `MainViewModel`'s own collection of `UserEventBus`. It reaches the viewmodel and Koin, so it is local work.
4. **A written proposal first:** turning the remaining exceptions into `Outcome` (D5), notices and command execution, scopes and dispatchers, `AppLifecycle`, the unlocked collections, any `ChatRepo` split.

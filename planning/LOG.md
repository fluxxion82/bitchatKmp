---
title: Log
tags: [bitchatkmp, planning, log]
---

# Log

Append only. Newest at the bottom. Template in `README.md`.

## 2026-10-12, cloud-claude

- Did: reviewed the architecture (four read-only review agents and a planning agent), then the owner reviewed the findings and decided D1 to D12. Implemented the first slice on `claude/naming-rule-and-safety-fixes`: seven commits, listed in `STATUS.md`.
- Corrected after the owner's review: "navigation in the domain" is withdrawn; `LocationRepo` and the Tor gate is not a leak; the discarded `verifyPasswordOwnership` result is not a bug; `ObserveMeshChannelPeople` already rethrew cancellation. Details at the top of `reference/2026-10-12-clean-architecture-review.md`.
- Pushed: `claude/naming-rule-and-safety-fixes` at `23dad85`, and this branch.
- Needs: the local agents to build, review with Codex and device-test the Apple dispatcher change (`STATUS.md`, Asks); the owner to answer Q1 to Q5.

## 2026-10-12, cloud-claude (later)

- Did: tried to delete `claude/clean-architecture-review-b6fcfl` as the owner asked (D13). GitHub refused with HTTP 403: this cloud session may push branches but not delete them.
- Needs: the owner, or a local agent with the owner's say-so, to delete it (`git push origin --delete claude/clean-architecture-review-b6fcfl`, or the "Delete branch" button on fluxxion82/bitchatKmp#1).

## 2026-10-12, local-claude

- Did: reviewed `claude/naming-rule-and-safety-fixes` at `23dad85` and the documents here, with two Codex (`gpt-6-astra`) reviews, then a second Codex round on the result ("nothing blocking"). Merged the branch onto the owner's `main`, which had four commits you could not see. Fixed what did not compile (`SettingsViewModel`, a test of `main`'s) and the desktop Koin `verify()`. Implemented Q3 and Q4. Took the Apple dispatcher commit out into `claude/apple-io-off-main`. Squash-merged the rest into `main` as `e4e5d94` (on the owner's machine; not pushed yet). Everything found, with evidence: `reference/2026-10-12-local-review.md`.
- Changed here: `CONVENTIONS.md` is new (the project's rules; you cannot see the owner's local `CLAUDE.md`). `README.md`: only `planning/` counts on this branch, questions live in `STATUS.md`. `DECISIONS.md`: D14 to D16 from the owner (short commit messages without trailers, few comments, squash merges). Notes at the top of the review and the plan. `STATUS.md` rewritten.
- Built and run: see `STATUS.md`.
- For cloud-claude, about the first slice: commit messages were long and carried two trailers each; D14 says one short line. Questions and "verified here" notes belong in `STATUS.md` and `LOG.md`. A property that shares a name with a function of the same class is not a rename you can check without compiling the module: say so when a change reaches past `:domain`.
- Needs: the owner to test `claude/apple-io-off-main` on an iPhone and to answer Q1, Q2 and Q5. cloud-claude to read `CONVENTIONS.md` and the local review, start from `main`, and propose the next slice here before writing code.

## 2026-10-12, local-claude (later)

- Did: on the owner's word pushed `main` (`e4e5d94`) and deleted `claude/naming-rule-and-safety-fixes` and `claude/clean-architecture-review-b6fcfl`. The owner answered Q1 to Q5: D17 to D20.
- Doing: D18 and D20 on a local branch; an entry here when they are on `main`.
- Needs: cloud-claude to start from `main`, read `CONVENTIONS.md` and the local review, and propose the next slice here first. Leave the files named in `STATUS.md` alone until then.

## 2026-10-12, local-claude (D18 and D20 landed)

- Did: `GetAppTheme` and `GetUserNickname` (one-shot); `SettingsViewModel`, `DmViewModel` and `LocationNotesViewModel` use them. Settings no longer reads background mode once: the collector shows the switch together with its first value (Codex caught that without this it could show a wrong value for a moment). Removed `ChatRepository.onPeersUpdated`, the short `sendGeohashMessage` and `sendMeshMessage`, and `BluetoothMeshDelegate.decryptChannelMessage`; their tests go through `sendMessage`. A mutation that lets an announced Noise key pick a queue still fails `ChatRepoNoiseKeyChatTest`.
- Pushed: `main` at `2e7e6aa`, on the owner's word. `claude/apple-io-off-main` is rebased onto it, still local.
- Needs: cloud-claude to start from `main` and propose the next slice here first. Nothing is held back any more.

## 2026-10-12, local-claude (note)

- `main` is `f13757f`, not `2e7e6aa`: the owner amended the follow-up commit with a one-line edit to `ObserveJoinedChannels` and pushed it. If you fetched `2e7e6aa`, fetch again.
- The owner confirmed D14: questions for another agent go in `STATUS.md`.
- `claude/apple-io-off-main` is on the owner's machine, one commit on `f13757f`, for the iPhone test.

## 2026-10-12, local-claude (Apple dispatchers landed)

- Did: the owner ran `claude/apple-io-off-main` on an iPhone (location, mesh chat with a board, Tor, proof of work, Settings, background, a soak; no error or crash) and chose to land it. `main` is `e0bbd4d`, pushed; the branch is gone.
- Still open, local work, on every platform: the collections without a lock (`reference/2026-10-12-local-review.md` §1). With `io` on one worker the only new overlap on iOS is `ChatRepo`'s named-channel state between the start-up restore block (main queue) and the worker.
- Seen on the way, older than this slice: the mesh service starts before anything has resolved `ChatRepo`, and until then it announces as "Anonymous" and has no delegate for what it receives. The iPhone did that for 15 minutes after a launch nobody was looking at. Task filed locally.
- Needs: cloud-claude to start from `main` and propose the next slice here first.

## 2026-10-13, cloud-claude

- Read: `CONVENTIONS.md`, `reference/2026-10-12-local-review.md`, `STATUS.md`, `DECISIONS.md` (D14 to D21), and `main` at `e0bbd4d`. Taken on board: one-line commit messages without trailers, few comments, questions here and not in commits; a viewmodel function named like an injected use case breaks a rename, so a rename that reaches past `:domain` is flagged as unverified.
- Proposed: the next slice, under Proposal in `STATUS.md`. Two candidates, the owner picks:
  - **A. Error model (D5), LoRa first.** A typed `Outcome`, a `LoRaFailure` that says why a LoRa change was not applied (no radio, protocol not registered, protocol keeps its own radio settings, start or reconfigure failed), the three LoRa use cases returning it, settings mapping each failure to its text. `JoinChannel` and `SetChannelPassword` follow. Reaches `data:repo` (`ChatRepository`'s two LoRa methods) and `presentation:viewmodel`: unverified here, needs a local build.
  - **B. Guardrails.** Rewrite plan §3 against D1 to D21, then a `:domain` architecture test for D1 and D2 (and that `domain` depends on no project module), with today's exceptions listed in the test. Verifiable here.
- Needs: the owner to choose A, B, or B then A, and the shape of `Outcome` if A.

## 2026-10-13, cloud-claude (later)

- The owner chose B then A, kept `Outcome` as it is and dropped its message (D22 to D24). The design for A is under Proposal in `STATUS.md`.
- Needs: the owner to answer Q6, what B holds.

## 2026-10-13, cloud-claude (B pushed)

- Did: the Konsist tests and the two renames they forced; details under Proposal in `STATUS.md`.
- Pushed: `claude/konsist-architecture-tests` at `98b1430`.
- Built and ran, in the stand-alone `:domain` build: `:domain:jvmTest` at `98b1430`, 154 tests, 0 failures, including the 3 domain rules. Each domain rule was checked against a planted violation and failed on it. The viewmodel rule was run from that build against the `presentation/viewmodel` sources: it failed on a planted clash and on `main`'s two clashes, and passes after the renames.
- Not built: `:presentation:viewmodel` (the renames, `ViewModelModule`, two tests, its `ArchitectureTest`). Konsist's module name `presentation/viewmodel` worked from the domain build; check it does from the module's own test task.
- Needs: a local build of `:presentation:viewmodel:jvmTest` and the gates that cover it. A, the error model, follows on its own branch.

## 2026-10-13, cloud-claude (A pushed)

- Did: the error model (D5, D23, D24) and `LoRaFailure`. Details and the user-visible text changes are under Proposal in `STATUS.md`.
- Pushed: `claude/error-model` at `951b652`, two commits on `e0bbd4d`.
- Built and ran at `951b652`:
  - `:domain:jvmTest` in the stand-alone build: 152 tests, 0 failures.
  - The viewmodel module's own `commonMain` and `jvmTest` in a second scratch build. It stubs `androidx.lifecycle.ViewModel`/`viewModelScope` and `resolveMediaToLocalPath`, and leaves out `ViewModelModule.kt`: 70 tests, 0 failures.
  - On a scratch merge of A and B: `:domain:jvmTest`, 155 tests including the 3 Konsist rules, 0 failures; the viewmodel tests, 70, 0 failures; the viewmodel Konsist rule, run from the domain build, passes.
- Not built: `:data:repo` (`ChatRepo`, `LoRaSavedConfigTest`), `:data:remote:rest:client` (`BaseApiClient`), `:presentation:viewmodel` with the real AndroidX and Koin, and every app.
- Needs: a local build and review of both branches. See Asks in `STATUS.md`.

## 2026-10-13, local-claude

- Did: built, reviewed and landed B (`98b1430`) and A (`951b652`), with Codex on the code (three rounds, the last "nothing blocking") and on these documents. Both built and passed as pushed, on every target: the renames and the `Outcome` change were complete. Fixed in review: the radio-settings failure named the selected protocol instead of the active one; "Unknown error" was a UI text in the domain; the Konsist helper matched text and its rules passed on an empty selection; nothing tested which failure `ChatRepo` picks. Details and every changed text: `reference/2026-10-13-local-review.md`.
- Also landed: the mesh no longer starts before the chat repository exists (`d1d6d67`).
- Changed here: `STATUS.md` rewritten (Q7 to Q9 for the owner); `CONVENTIONS.md` has a Failures section and two lines on Konsist and scratch builds; `reference/2026-10-13-local-review.md` is new.
- For cloud-claude: thank you for the one-line commits and for saying exactly what was not built. Two things. A failure must carry the fact it reports (the active protocol) and not take it from what the screen has selected. And list every text that changes, by path: the LOG named one of four.
- Built and ran: see `STATUS.md`.
- Needs: the owner to answer Q7 to Q9. cloud-claude to fetch `main` and wait for Q8.

## 2026-10-13, local-claude (pushed)

- Did: on the owner's word pushed `main` (`d1d6d67`) and deleted `claude/konsist-architecture-tests` and `claude/error-model`. The owner accepted the changed texts (D26) and chose the next slice (D27).
- Needs: cloud-claude to fetch `main` and start D27 as described under Asks in `STATUS.md`: the test list here first, then the branch.

## 2026-10-13, cloud-claude (D27 test list)

- Read: `STATUS.md`, `reference/2026-10-13-local-review.md`, the Failures section of `CONVENTIONS.md`, D26 and D27, and `main` at `d1d6d67`. Taken on board: a failure carries the fact from where it happened; list every changed text by path; name the input commits and the tasks of a scratch build, and do not call a stubbed build by a module's task name.
- Plan: two branches from `d1d6d67`, both inside `:domain`, in this order. Each test is checked to bite: for each case, a mutation of the use case that breaks it must fail it.

**1. `claude/error-contract-tests`.** Tests only; no file under `commonMain` changes.

`SetChannelPasswordTest` (new). "No commitment" means `getChannelKeyCommitment` returns null.
- No commitment, not the owner: `OnlyCreatorCanSetPassword`. Nothing written, no event.
- No commitment, the owner: the new password is written, `ChannelOwnershipVerified(name)` is published, success.
- No commitment, the write throws with a message, and without one: `PasswordNotSaved(message)` and `PasswordNotSaved(null)`. No event.
- A commitment, no current password, ownership verifies: success, nothing written. It does not verify: `WrongPassword`.
- A commitment and a current password whose commitment differs: `WrongPassword`. Nothing written, `verifyPasswordOwnership` not called.
- The current password matches, the owner: the new password is written, the event is published, `verifyPasswordOwnership` is not called.
- The current password matches, not the owner: `verifyPasswordOwnership(name, current)` is called before the write, then success. The stub returns true; the test does not pin what a false result does (it is called for its side effect, see the 2026-10-12 review).
- The change's write throws: `PasswordNotSaved(message)`.
- Cancellation out of the write, on the initial path and on the change path: rethrown, not a failure.

`JoinChannelTest` (added):
- A protected channel without a password: `WrongPassword`. No key derived, nothing written, no join, no event.
- A protected channel and a password whose commitment differs from the advertised one: `WrongPassword`. Nothing written, no join.
- A protected channel with no advertised commitment: any password is taken (plan §5.1 records this). It is written, the join is made with it, `ChannelJoined` and `ChannelListUpdated` are published, and the result is an existing channel.
- A protected channel and the right password: written, joined, success.

`SwitchLoRaProtocolTest`: each of `NoRadio`, `NotRegistered`, `KeepsOwnRadioSettings(MESHTASTIC)` and `NotApplied` is returned as it came, with the preference saved before the call. An exception that is not cancellation propagates, after the save.

`ReconfigureLoRaSettingsTest`: the same four failures and success, for region and for power, saved before the call. An exception that is not cancellation propagates, after the save.

Not tested on purpose: an exception from discovery, key derivation or verification (D5 is not finished, see `STATUS.md` Next), and the event publish inside the write's `try` (local review §3).

**2. `claude/observer-fixes`.**
- `ObserveAppTheme` re-reads only on `ThemeUpdated`. This is the one change to `commonMain`. Its pattern stays (D4): read on start, re-read on a matching event, no shared helper.
- `ObserveAppThemeTest` (new):
  - With `BackgroundModeChanged` in the replay slot, the start emits one theme from one read. This fails on `main` and is the defect being fixed.
  - `ThemeUpdated` re-reads.
  - A later `BackgroundModeChanged` does not.
- `ObserveJoinedChannelsTest`: with `ChannelChanged` in the replay slot, the start emits once from one read.
- `ObserveUserNicknameTest`: with `StateChanged` in the replay slot, the start emits once from one read.
- A matching event in the replay slot still causes one re-read at the start. That is today's behaviour on every bus (D4) and is left as it is.

Each branch: one commit with a one-line message, then a LOG entry naming the input commit, the scratch build's tasks and what was not run.

## 2026-10-13, cloud-claude (D27 pushed)

- Did: the test list above, as listed, on two branches.
  - The one change to the list: the protected-channel-without-a-commitment case stubs the key derivation. A relaxed mock cannot return a `ByteArray` from a suspend call.
  - No test showed a defect, so there is nothing new under Open questions.
- Pushed:
  - `claude/error-contract-tests` at `ff92f7c`, one commit on `d1d6d67`. It changes test files only:
    - `SetChannelPasswordTest` (new, 10 tests)
    - `JoinChannelTest` (+4)
    - `SwitchLoRaProtocolTest` (one test now loops over all four failures, +1 for exceptions)
    - `ReconfigureLoRaSettingsTest` (its loops cover all four failures and success, +1 for exceptions)
  - `claude/observer-fixes` at `cb67ad6`, one commit on `d1d6d67`:
    - `ObserveAppTheme.kt` re-reads only on `ThemeUpdated`.
    - `ObserveAppThemeTest` (new, 3 tests), `ObserveJoinedChannelsTest` (+1), `ObserveUserNicknameTest` (+1).
- Texts: none changed.
- Behaviour, `claude/observer-fixes` only: `MainViewModel`'s theme collector no longer gets the theme again when background mode is toggled, nor at its start when `BackgroundModeChanged` was the bus's last event. `SetAppTheme` is the only publisher of `ThemeUpdated`, and it still re-reads.
- Built and ran:
  - The stand-alone `:domain` build: `scratchpad/domain-build` includes the repository's own `domain/` with the Kotlin Multiplatform plugin and no Android plugin. It ran `:domain:jvmTest`, the full task, including the three Konsist rules:
    - At `ff92f7c`: 172 tests, 0 failures.
    - At `cb67ad6`: 161 tests, 0 failures. `d1d6d67` has 156.
  - On `d1d6d67` before the fix: the two new `ObserveAppThemeTest` cases that check `BackgroundModeChanged` failed in 3 runs out of 3, with `--rerun-tasks`. They pass after it, also 3 out of 3.
  - Mutations, each applied alone to the `commonMain` file, then the test class run with `--tests`, then the file restored. Every one failed at least one of the new tests:
    - `SetChannelPassword`: 12. No owner check; no event on the first password or on a change; the message dropped from either write; cancellation swallowed on either path; ownership always verified; the ownership path writing the password; no commitment check on a change; owner and non-owner swapped; ownership claimed after the write instead of before.
    - `JoinChannel`: 5. No password read as `JoinFailed`; no commitment check; a missing commitment refusing every password; the password not written; joined without the password.
    - The LoRa use cases: 8. A failure turned into `NotApplied` (`NoRadio` for a switch and for power, `NotRegistered` for region); `KeepsOwnRadioSettings` losing its protocol; the protocol saved after the call; an exception turned into `NotApplied` (switch, region, power).
    - The observers: 4. `ObserveJoinedChannels` and `ObserveUserNickname` re-reading on every event; `ObserveAppTheme` re-reading on every event (that is `main`'s behaviour) and never re-reading.
- Not built: anything above `:domain`. Only `ObserveAppTheme` changes outside tests, and its one caller, `MainViewModel`, collects it unchanged.
- Needs: a local build and review of both branches.

## 2026-10-13, local-claude (D27 reviewed)

- Did: built, reviewed and landed `claude/error-contract-tests` (`ff92f7c`) and `claude/observer-fixes` (`cb67ad6`), with Codex on the code (three rounds) and on these documents. Both built and passed as pushed. Codex went through all 24 test methods: each fails for the rule it names, and your 29 mutations are plausible against them. Good work.
- Found: narrowing `ObserveAppTheme` was right, and it uncovered an older defect. A wipe resets the stored theme in `AppRepo.clearData()` and published nothing, so the screen kept the old theme; before the filter a background-mode toggle refreshed it by accident. `ClearAllData` now publishes `ThemeUpdated` right after the app store is cleared, so also when a later store fails. `SetAppTheme` was the only publisher of the event but not the only writer of the theme: see Observers and events in `CONVENTIONS.md`.
- Changed in your tests: the no-commitment join test says in its name that it records today's behaviour, and checks join before each notification without ordering the two.
- Changed here: `STATUS.md` rewritten (Q9 and Q10 for the owner); `reference/2026-10-13-local-review-d27.md` is new; `CONVENTIONS.md` has an Observers and events section; the plan has a status note for today.
- Built and ran: see `STATUS.md`.
- Needs: the owner to answer Q9 and Q10 and to push. cloud-claude to wait for that.

## 2026-10-13, local-claude (D27 pushed)

- Did: on the owner's word pushed `main` (`ff4697c`) and deleted `claude/error-contract-tests` and `claude/observer-fixes`. The owner chose the next slice (D28, D29).
- Needs: cloud-claude to fetch `main` and start D29 as described under Asks in `STATUS.md`: the plan here first, then the two branches.

## 2026-10-13, cloud-claude (D29 plan)

- Read:
  - `STATUS.md`, `reference/2026-10-13-local-review-d27.md`, the Observers and events section and the Konsist and scratch-build lines of `CONVENTIONS.md`, and D28 and D29.
  - `main` at `ff4697c`, including `0a97ab8`, `e231574` and `068261d`. Neither branch touches `PrivateTextLimit` or `GetPrivateTextLimit`.
- Taken on board:
  - Before narrowing an observer, find every writer of what it observes.
  - A test that records undecided behaviour says "today" in its name.
  - My "no test showed a defect" overstated it; the theme tests did reproduce the defect they were written for.
- Plan: two branches from `ff4697c`, both inside `:domain`, in this order.

**1. `claude/konsist-placement-rules`.** Only `domain/src/jvmTest/.../ArchitectureTest.kt` changes.
- `everyRepositoryInterfaceSitsInItsFeaturesRepositoryPackage`: every interface in the module's production sources whose name ends in `Repository` is in `com.bitchat.domain.<feature>.repository`. A feature is one segment after `com.bitchat.domain`, not `base` or `di`; this is the same test the use-case rule already applies. It asserts first that it selected something.
- `everyEventBusInterfaceSitsInItsFeaturesEventbusPackage`: the same for `EventBus` and `.eventbus`.
- No exception list. Today there are 9 repositories and 7 buses, all in place.
- Each rule is checked to fail on a planted interface. Each plant is added alone and removed after its run:
  - `ProbeRepository` in `chat.model` (a feature, wrong subpackage).
  - `ProbeRepository` in `base.repository` (not a feature).
  - `ProbeRepository` in `jvmMain`, package `com.bitchat.domain` (another source set).
  - `ProbeEventBus` in `chat` (the feature root).
  - `ProbeEventBus` in `chat.repository` (the wrong kind of package).
  - `ProbeEventBus` in `appleMain` (a source set the JVM build does not compile, which Konsist still reads).
- The non-empty assertion is checked by changing a selection's suffix so it matches nothing. The rule must then fail.

**2. `claude/domain-file-names`.** Moves only.
- `git mv location/model/ChannelID.kt location/model/Channel.kt`
- `git mv connectivity/model/BluetoothEvent.kt connectivity/model/BluetoothConnectionEvent.kt`
- `git mv connectivity/model/LocationEvent.kt connectivity/model/LocationConnectionEvent.kt`
- `Version`, with its KDoc, moves out of `initialization/models/AppInformation.kt` into a new `initialization/models/Version.kt`. Git cannot follow a split; the other three are pure renames.
- Same packages, declarations and `@SerialName`s. No `models` to `model` move.
- None of the four files has a top-level function, so no JVM facade class (`ChannelIDKt` and the like) exists to change name. Nothing in the repository names these files.
- Checked by:
  - `git diff -M --name-status` against `ff4697c`: three `R100` and the one split, nothing else.
  - The declarations before and after are the same text.
  - `:domain:jvmTest`.

Each branch: one commit with a one-line message, then a LOG entry naming the input commit, the scratch build's tasks and what was not run.

## 2026-10-13, cloud-claude (D29 pushed)

- Did: the plan above, as written. No defect found; nothing new under Open questions.
- Pushed:
  - `claude/konsist-placement-rules` at `d79c235`, one commit on `ff4697c`. Only `domain/src/jvmTest/.../ArchitectureTest.kt` changes: two rules sharing one helper, `assertPlacedIn(suffix, subpackage)`, which reuses `isFeaturePackage`.
  - `claude/domain-file-names` at `967d59b`, one commit on `ff4697c`. `git diff -M --name-status ff4697c`:
    - three `R100`: `ChannelID.kt` to `Channel.kt`, `BluetoothEvent.kt` to `BluetoothConnectionEvent.kt`, and `connectivity/model/LocationEvent.kt` to `LocationConnectionEvent.kt`;
    - `M AppInformation.kt` and `A Version.kt`. The old `AppInformation.kt` is exactly the new one followed by `Version.kt` without its package line, checked by a script.
    - Nothing else. `git log --follow` on `Channel.kt` goes back past the rename.
- Texts and behaviour: none changed.
- Built and ran:
  - Every run used the stand-alone `:domain` build (`scratchpad/domain-build`). It includes the repository's own `domain/` with the Kotlin Multiplatform plugin and no Android plugin, and runs `:domain:jvmTest`, the full task.
  - At `d79c235`: 181 tests, 0 failures. That is `ff4697c`'s 179 plus the 2 new rules.
  - At `967d59b`: 179 tests, 0 failures.
  - The selections, printed once from a temporary `println` that was then removed: `Repository` selects 9 interfaces (App, BlockList, Chat, Connectivity, LoRaSettings, Location, Nostr, Tor, User), and `EventBus` selects 7 (App, Chat, Connection, Location, Nostr, Tor, User).
  - Planted interfaces. Each was written alone, `ArchitectureTest` was run with `--tests`, and the file was deleted. Each misplaced plant failed only its own rule:
    - `ProbeRepository` in `chat.model`, in `base.repository`, and in `jvmMain` under `com.bitchat.domain`.
    - `ProbeEventBus` in `chat`, in `chat.repository`, and in `appleMain` under `app`. The JVM build does not compile `appleMain`; Konsist read it all the same.
  - Two correctly placed plants (`chat.repository.ProbeRepository`, `tor.eventbus.ProbeEventBus`) passed both rules.
  - The suffix changed to `Repositories` so the selection is empty: the repository rule failed on its non-empty assertion. The change was then reverted.
- Not built: anything above `:domain`. Neither branch changes a declaration, a package or an import, but the renames only show their full effect in the apps' builds.
- Needs: a local build and review of both branches.

## 2026-10-13, local-claude (D28 and D29 reviewed and landed locally)

- Read: both branches, the two D29 entries above, `STATUS.md`.
- Built: both branches together on `main` at `b572169`, every target. They built and passed as pushed; neither needed a change.
- Found, older than the branches, and fixed in a third commit:
  - The build did not run the rules again after a change the JVM does not compile into different classes. A misplaced interface in `linuxMain` passed from the build cache. Your scratch build has no cache, so it could not show this.
  - `@Suppress("assertPlacedIn")` on a misplaced interface switched both rules off; the older rules had the same way out under their own names (Codex found it).
- Landed on local `main`, not pushed: `dca893b` (your rules, unchanged), `519ba25` (your renames, unchanged), `6aefc15` (the two fixes).
- Written: `reference/2026-10-13-local-review-d29.md`; two lines under Verification in `CONVENTIONS.md`; `STATUS.md`.
- Corrections to the D29 entries are in the review, section 4. The one that matters: the `appleMain` plant failed in your build and would have passed in the repository's.
- Needs: the owner's word on the push and on Q11. cloud-claude: for a new rule use `assertEach`, and read a plant's task outcome before believing a pass.

## 2026-10-13, local-claude (D28 and D29 pushed, D30 decided)

- Did: on the owner's word pushed `main` (`6aefc15`) and deleted `claude/konsist-placement-rules` and `claude/domain-file-names`. The owner chose the next slice (D30).
- Needs: cloud-claude to fetch `main` and start D30 as described under Asks in `STATUS.md`: the plan with the list of writers here first, then the branch.

## 2026-10-14, cloud-claude (D30 plan)

- Read: `STATUS.md`, `reference/2026-10-13-local-review-d29.md` (with section 5), the Verification and Observers and events sections of `CONVENTIONS.md`, D30, and `main` at `6aefc15`.
- Taken on board:
  - A new rule uses `assertEach`, never a Konsist assertion.
  - A plant proves nothing until the task's outcome is read.
  - `GetLastFixInfo` is gone and `getAvailableGeohashChannels()` returns `NearbyChannels`. Nothing in this slice touches either.

**The writers.** Paths are from `6aefc15`. "Publishes" means: is an event the observer re-reads on published after the write?

What `GetUserState` reads, in its order. `ObserveUserState` re-reads on `StateChanged` and `LoginChanged`.

| Input | Writer | Publishes |
|---|---|---|
| `AppRepository.hasRequiredPermissions()` | The user answering the permission request: `PermissionsViewModel` then calls `SaveUserStateAction(GrantPermissions)` | yes, `StateChanged` |
| same | The user revoking a permission in the system's settings while the app runs | **no** |
| `ConnectivityRepository.isBluetoothEnabled()` | The user switching Bluetooth in the system. The platform `ConnectionEventBus` emits a `BluetoothConnectionEvent`, which only `ChatRepo` reads, to start or stop the mesh | **no** |
| `ConnectivityRepository.isLocationServicesEnabled()` | The user switching location services in the system. `LocationConnectionEvent`, and `ObserveLocationServicesEnabled` for the header | **no** |
| `AppRepository.isBatteryOptimizationSkipped()` | `SkipBatteryOptimization`, then `SaveUserStateAction(HandledOptimizations)` (`BatteryOptimizationViewModel`) | yes |
| same | `AppRepo.clearData()` resets it inside the wipe; `ClearAllData` publishes after the barrier | yes |
| `AppRepository.getBatteryOptimizationStatus()` | `DisableBatteryOptimization` (opens the system screen), then `HandledOptimizations` | yes |
| same | The user changing it in the system's settings at any other time | **no** |
| `UserRepository.getUserState()` | `SaveUserStateAction`, on its blocking path and on its normal path | yes, both |
| same | `HeadlessUserStateInitializer` (`data:repo`), when no state is saved | yes, `LoginChanged` |
| same | The wipe: `UserRepo.clearData()` does not touch the saved state. `ClearAllData` publishes `StateChanged` anyway | yes |

What `GetAllFavorites` reads (`UserRepository.getAllFavorites()`). `ObserveFavorites` re-reads on `FavoriteStatusChanged`.

| Writer | Publishes |
|---|---|
| `ToggleFavorite`, through `UserRepository.updateFavorite` | yes |
| A favourite notification from a peer: `ChatRepo.handleFavoriteNotificationIfNeeded` | yes, when a record changed |
| The trim at start: `ChatRepo`'s `init` cuts records the user never favourited to the limit | yes, once, when it removed one |
| The wipe: `UserRepo.clearData()` calls `clearAllFavorites()`; `ClearAllData` publishes only `StateChanged` | **no** |
| `UserRepository.saveFavorite`, `deleteFavorite` | no caller outside `UserRepo` today |

**Findings.** I report these and do not fix them; none is in `:domain`'s use cases except the wipe.
- **F1. Nothing publishes when the system changes a blocking input while the app runs.** That covers a permission, Bluetooth, location services and the battery status. The user state follows only at the next `SaveUserStateAction`, which every navigation in the app runs, or at a restart. That is today's behaviour, and it holds the same with `ObserveUserState`. I have not checked which platforms end the process on a revoke.
- **F2. The wipe clears the favourites and publishes no `FavoriteStatusChanged`.** Today `MainViewModel.handleTripleClick` calls `goToNextStep()` after the wipe but not `refreshFavorites()`. The sidebar keeps the old favourites until the next favourite change or a restart. `ObserveFavorites` will not fix that alone. The fix is one line in `ClearAllData` (domain), like the theme in D27. It changes a use case outside this slice's ask, so it is Q12.
- **F3, near this slice.** The wipe also gives the user a new nickname (`UserRepo.clearData()` calls `updateNickname`) and publishes no `NicknameUpdated`. `ObserveUserNickname` therefore keeps showing the old one. Same shape as F2; it goes in Q12 with it.

**The use cases.**
- `user/ObserveUserState : Usecase<Unit, Flow<UserState>>`. It takes `GetUserState` and `UserEventBus`. It emits `getUserState()` on start, and again on `StateChanged` and on any `LoginChanged`.
- `user/ObserveFavorites : Usecase<Unit, Flow<Map<String, FavoriteRelationship>>>`. It takes `UserRepository` and `UserEventBus`, as `ObserveUserNickname` does. `GetAllFavorites` is one call to the same method, with no rule to keep. It emits on start and on any `FavoriteStatusChanged`.
- Both have the shape of `ObserveUserNickname`: `channelFlow`, `onStart`, `when` with `else -> Unit`. No `distinctUntilChanged`, no conflation.
- `domainModule` gets `single` lines next to their siblings. `GetUserState` and `GetAllFavorites` stay.

**Tests.** Each uses `InMemoryUserEventBus` and counts the reads with `coVerify`.
- `ObserveUserStateTest` builds a real `GetUserState` on mocked repositories, so the test shows its rules come through.
  - The start emits the saved state.
  - A blocking input comes through: Bluetooth off gives `BluetoothDisabled`, and the saved state is not read.
  - `StateChanged` gives one more read, and `LoginChanged` gives one more.
  - `FavoriteStatusChanged` after the start gives no read.
  - `NicknameUpdated` in the replay slot at the start gives no second read.
  - Two `StateChanged` with the same state give two emissions.
  - Today, `StateChanged` in the replay slot at the start gives a second read. That is D4's pattern, and it matters for the local switch: `MainViewModel` does the same now.
- `ObserveFavoritesTest`, the same set for favourites:
  - the first value;
  - `FavoriteStatusChanged` gives one more read;
  - `StateChanged` after the start gives none;
  - `StateChanged` in the replay slot gives none;
  - two `FavoriteStatusChanged` give two emissions of the same map;
  - today, `FavoriteStatusChanged` in the replay slot gives a second read.
- Each is checked to bite with mutations: a branch dropped, re-reading on every event, `distinctUntilChanged` added, no read on start, and `ObserveUserState` reading `UserRepository` itself instead of `GetUserState`. Each run's outcome is read from the task.

**For the local switch (not this branch).**
- `MainViewModel` reads the favourites once at init and on `FavoriteStatusChanged`. It also calls `refreshFavorites()` after its own `toggleFavorite`, which publishes the event as well, so a toggle refreshes twice today. `ObserveFavorites`' start read takes the place of the init read.
- `goToNextStep()` has two callers besides the bus: the wipe and the replay.

One branch, `claude/observe-user-state`, from `6aefc15`. One commit with a one-line message, then a LOG entry.

## 2026-10-14, cloud-claude (D30 pushed)

- Did: the plan above, as written.
- Pushed: `claude/observe-user-state` at `347e193`, one commit on `6aefc15`, 5 files:
  - `user/ObserveUserState.kt` and `user/ObserveFavorites.kt` (new);
  - two `single` lines and two imports in `DomainModule.kt`;
  - `ObserveUserStateTest` (new, 8 tests) and `ObserveFavoritesTest` (new, 6 tests).
- Texts and behaviour: none changed. Nothing uses the two use cases until the local switch.
- Built and ran:
  - Every run used the stand-alone `:domain` build (`scratchpad/domain-build`). It includes the repository's own `domain/` with the Kotlin Multiplatform plugin and no Android plugin, and runs `:domain:jvmTest`.
  - The full task at `347e193`: 197 tests, 0 failures. That is `6aefc15`'s 183 plus 14. It includes the five Konsist rules, which pass with the two new `Observe*` classes. The task line read `> Task :domain:jvmTest`; the scratch build has no build cache.
  - Mutations, ten in all. Each was applied alone to the `commonMain` file, the test class was run with `--tests`, and the file was restored. Every one failed at least one test:
    - `ObserveUserState`, 6:
      - `LoginChanged` not matched;
      - `StateChanged` not matched;
      - a re-read on every event;
      - `distinctUntilChanged` added;
      - no read on start;
      - a rule of its own (anything not `Active` read as the default chat), which hides a blocking state. It stands in for "reads the repository instead of `GetUserState`": the class has no repository to read without a constructor change.
    - `ObserveFavorites`, 4: not re-read on `FavoriteStatusChanged`; a re-read on every event; `distinctUntilChanged` added; no read on start.
  - My script's task-outcome check matched `> Task :domain:jvmTestProcessResources` instead of the test task. Each mutation run still left fresh failures in the test results, so the task ran each time. The script is fixed for next time.
- Not built: anything above `:domain`. The desktop Koin `verify()` will see two new `single`s whose dependencies (`GetUserState`, `UserRepository`, `UserEventBus`) are already in the graph.
- Needs: a local build and review of the branch, then the `MainViewModel` switch. The owner's answer to Q12.

## 2026-10-14, local-claude (D30 reviewed and landed locally)

- Read: the branch, the two D30 entries above, `STATUS.md`.
- Built: the branch on `main` at `845c44c`, every target. It built and passed as pushed. The writer tables are right in substance; corrections are in the review, section 3.
- Did the local half of D30: `MainViewModel` collects `ObserveUserState` and `ObserveFavorites` and no longer imports `UserEventBus`.
- Found by Codex once the view model used them, and fixed:
  - Both use cases read before they subscribed, so an event published during the first read could be lost. They now subscribe first.
  - `toggleFavorite`'s own refresh could show an older map. It is gone, and so is `GetAllFavorites`.
- Not taken: keeping state observation alive after a failed read. The reason is in the review, row 3; it belongs in the D5 proposal.
- Landed on local `main`, not pushed: `e5c13ce` (your use cases, with the subscribe-first fix and one test each), `fd93095` (the switch).
- Written: `reference/2026-10-14-local-review-d30.md`, a line under Observers and events in `CONVENTIONS.md`, and `STATUS.md` with Q12 and Q13.
- Needs: the owner's word on the push, Q12 and Q13.

## 2026-10-14, local-claude (D30 pushed, D31 and D32 decided)

- Did: on the owner's word pushed `main` (`fd93095`) and deleted `claude/observe-user-state`. The owner answered Q12 (D31) and Q13 (D32).
- Needs: cloud-claude to fetch `main` and start as described under Asks in `STATUS.md`: the plan here first, with the writers for the second part, then one branch with two commits.

## 2026-10-15, cloud-claude (D31 and D32 plan)

- Read:
  - `STATUS.md`, `reference/2026-10-14-local-review-d30.md` and the new line under Observers and events.
  - D31 and D32, and `main` at `fd93095`.
- Taken on board:
  - Subscribe before the first read: copy the `UNDISPATCHED` launch into a `Channel(BUFFERED)` from `ObserveUserState`.
  - `GetAllFavorites` is gone.
  - In my D30 table the location row mixed two settings.
  - Not every navigation runs `SaveUserStateAction`.
- Plan: one branch, `claude/wipe-events-and-chat-state`, from `fd93095`, two commits in this order.

**Commit 1, D31.**
- `UserEvent.FavoritesCleared` (a `data object`).
- `ClearAllData` publishes `UserEvent.NicknameUpdated`, then `UserEvent.FavoritesCleared`, right after `userRepository.clearData()`, inside `chatNotices.reset { }`.
- `ObserveFavorites` reads again on `FavoritesCleared` as well as on `FavoriteStatusChanged`.
- Every `when` over `UserEvent`, in every layer, `fd93095`. Tests and Swift have none.

| Where | Kind | Change |
|---|---|---|
| `data/repo/.../di/LoRaAppInitializer.kt:63` | exhaustive, no `else` | **`UserEvent.FavoritesCleared -> Unit`**, next to `is UserEvent.FavoriteStatusChanged -> Unit`. A local agent builds it |
| `data/repo/.../repositories/ChatRepo.kt:266` | `else -> null` | none |
| `domain/.../user/ObserveFavorites.kt` | `else -> Unit` | the new branch |
| `domain/.../user/ObserveUserNickname.kt` | `else -> Unit` | none; it already reads again on `NicknameUpdated` |
| `domain/.../user/ObserveUserState.kt` | `else -> Unit` | none |

- Tests:
  - `ClearAllDataTest`, in the shape of the theme tests. A real `ObserveUserNickname` and a real `ObserveFavorites` run over a real `InMemoryUserEventBus` and show the new nickname and the empty favourites after a wipe. A second pair does the same when the app store, cleared right after the user store, throws.
  - `ObserveFavoritesTest`: `FavoritesCleared` reads the favourites again.
  - Each wipe test is checked to fail without its publish, and with the publish moved after the app store.

**Commit 2, D32. The writers first.** `ChatViewModel` reads `GetUserState` on start and on every `ChatEvent.ChannelChanged`. What it uses is `Active(Chat(channel))`, `BluetoothDisabled` (it then observes the mesh for LoRa), or anything else (no channel).

| What changes | Writer | Publishes `ChannelChanged` |
|---|---|---|
| The saved state, a chat (`UserStateAction.Chat`, `MeshDM`, `NostrDM`) | `SaveUserStateAction`: `chatRepository.setSelectedChannel` publishes once **before** the save (`ChatRepo.kt:1988`), and the action publishes again after it | yes, twice. This is the repeat the comment in `ChatViewModel` is about |
| The saved state, `GrantPermissions` and `HandledOptimizations` | `SaveUserStateAction`, after the save, when the new state is a chat | yes when it is `Active(Chat(Mesh))`, no when it is `BatteryOptimization` |
| The saved state, `Locations`, `Settings`, `LocationNotes` | `SaveUserStateAction` | no. The state is not a chat, so `ChatViewModel` keeps its last channel; that is today's behaviour |
| The saved state, a blocking state | `SaveUserStateAction`'s blocking path: `setUserState(blocking)`, `StateChanged` only | **no** (G1) |
| The saved state when none is saved | `HeadlessUserStateInitializer` (`data:repo`): `Active(Chat(Mesh))`, `LoginChanged` only | **no** (G2) |
| A named channel already joined | `JoinChannel`: no write of the state | yes, a repeat read. The caller then saves through `SaveUserStateAction` |
| The wipe | `UserRepo.clearData()` keeps the saved state; `StateChanged` only | no, and nothing it reads changed |
| Permissions, Bluetooth, location services and battery in the system | the platform | **no** (D30's F1) |
| `ChatRepo.setSelectedChannel` | called only by `SaveUserStateAction` | yes |

Findings, reported and not fixed:
- **G1.** When `SaveUserStateAction` saves a blocking state, no `ChannelChanged` follows. `ChatViewModel` does not move to "Bluetooth disabled: observe the mesh for LoRa" until the next `ChannelChanged`. The local switch should check whether `ChatViewModel` also needs `StateChanged`. That would be the owner's call.
- **G2.** The headless initializer publishes no `ChannelChanged`. It has no visible effect today: with no saved state, `GetUserState` already returns `Active(Chat(Mesh))`, the same as what the initializer saves.

**The use case.**
- Proposed name: **`ObserveUserStateOnChannelChange`**. It gives `UserState`, and the plain name `ObserveUserState` is taken by the observer that follows `UserEvent`s, so the qualifier names its trigger. A shorter alternative is `ObserveChatUserState`; the owner may prefer it.
- `chat/ObserveUserStateOnChannelChange : Usecase<Unit, Flow<UserState>>`. It takes `GetUserState` and `ChatEventBus`.
- It subscribes first, in the shape of `ObserveUserState`, then emits `getUserState()` on start and on every `ChannelChanged`. Nothing on any other event. No `distinctUntilChanged`.
- Its `single` line in `domainModule`, next to `ObserveJoinedChannels`.
- `ObserveUserStateOnChannelChangeTest`, in the shape of `ObserveUserStateTest` with a real `GetUserState`:
  - the start;
  - a blocking input coming through;
  - `ChannelChanged` reads again;
  - an unrelated `ChatEvent` after the start gives no read, and none when it sits in the replay slot at the start;
  - two `ChannelChanged` give two emissions of the same state;
  - today, a `ChannelChanged` in the replay slot reads a second time at the start;
  - a `ChannelChanged` published during the first read is read after it.
- Mutations: the branch dropped, a re-read on every event, `distinctUntilChanged` added, no read on start, and the read made before the subscription. That last one is the old shape, and the during-the-first-read test must catch it.

**Not in this branch:** `ChatViewModel`, `LoRaAppInitializer` and the Konsist rule for view models.

## 2026-10-15, cloud-claude (D31 and D32 pushed)

- Did: the plan above, as written.
- Pushed: `claude/wipe-events-and-chat-state` at `7327b2d`, two commits on `fd93095`:
  - `651f462` (D31):
    - `UserEvent.FavoritesCleared`.
    - `ClearAllData` publishes `NicknameUpdated` and `FavoritesCleared` right after `userRepository.clearData()`, inside the barrier, with a one-line comment like the theme's.
    - `ObserveFavorites` reads again on `FavoritesCleared`.
    - Tests: four in `ClearAllDataTest` (nickname and favourites, after a wipe and after a wipe whose app store throws) and one in `ObserveFavoritesTest`. `wipe()` takes the user store and the user bus as parameters now; the other tests are unchanged.
  - `7327b2d` (D32):
    - `chat/ObserveUserStateOnChannelChange.kt`, in the subscribe-first shape of `ObserveUserState`. The comment on keeping repeats is moved from `ChatViewModel`.
    - Its `single` in `domainModule`.
    - `ObserveUserStateOnChannelChangeTest`, 8 tests.
- **Not built, needs a local edit:** `data/repo/.../di/LoRaAppInitializer.kt:63` matches `UserEvent` exhaustively. Without `UserEvent.FavoritesCleared -> Unit`, `:data:repo` does not compile on this branch. Every other `when` over `UserEvent` has an `else` (table in the plan).
- Texts: none changed. Behaviour: after a wipe, the header's nickname and the sidebar's favourites follow it (D31). Nothing uses the D32 use case until the local switch.
- Built and ran:
  - Every run used the stand-alone `:domain` build (`scratchpad/domain-build`). It includes the repository's own `domain/` with the Kotlin Multiplatform plugin and no Android plugin, and has no build cache.
  - The full `:domain:jvmTest` (task line `> Task :domain:jvmTest`, 0 failures):
    - at `651f462`: 206 tests;
    - at `7327b2d`: 214 tests, the five Konsist rules included.
    - That puts `fd93095` at 201, counted from the tests added.
  - Mutations, ten in all. Each was applied alone, the class was run with `--tests`, the task line read `> Task :domain:jvmTest`, and the file was restored. Every one failed at least one test:
    - `ClearAllData`: no `NicknameUpdated`; no `FavoritesCleared`; both moved after the app store; both moved after the barrier. The last two fail only the tests whose app store throws.
    - `ObserveFavorites`: `FavoritesCleared` not matched.
    - `ObserveUserStateOnChannelChange`:
      - `ChannelChanged` not matched;
      - a re-read on every event;
      - `distinctUntilChanged` added;
      - no read on start;
      - the read made before the subscription. That is the old shape, and only the during-the-first-read test catches it.
- Not built: `:data:repo` (see above), `:presentation:viewmodel` and every app.
- Needs: the `LoRaAppInitializer` line, a local build and review, then the `ChatViewModel` switch and its Konsist rule. The owner's word on the name.

## 2026-10-15, cloud-claude (D33: `ObserveActiveChannel` replaces the D32 commit)

- The owner turned down the name `ObserveUserStateOnChannelChange`: it breaks D1. Of three options the owner chose a separate use case named for the model it gives (D33). The other two were merging into `ObserveUserState` and an `ObserveUserState` with a parameter.
- Rewrote my own unreviewed branch so it stays at two commits:
  - `claude/wipe-events-and-chat-state` is now at **`f84bbc9`**. `651f462` (D31) is unchanged.
  - The D32 commit `7327b2d` is replaced by `f84bbc9` "Add ObserveActiveChannel". Pushed with `--force-with-lease` on `7327b2d`.
  - If you fetched `7327b2d`, fetch again.
- `chat/ObserveActiveChannel : Usecase<Unit, Flow<Channel?>>`:
  - It takes `GetUserState` and `ChatEventBus`.
  - It has the same subscribe-first shape and the same triggers (start, every `ChannelChanged`, repeats kept) as the commit it replaces.
  - It emits the channel `ChatViewModel` follows today: a saved chat's channel; `Channel.Mesh` for `BluetoothDisabled` (LoRa); null for any other state. That mapping moves from `ChatViewModel` into the domain.
  - Its `single` is in `domainModule`. `ObserveActiveChannelTest` has 10 tests: the 8 from before, plus a non-chat state giving null and another blocking state giving null.
- **For the local switch:** today, for `BluetoothDisabled`, `ChatViewModel` sets only `_currentChannel` to the mesh and leaves `_state` (`selectedLocationChannel`, `currentGeohash`, `currentChannel`) as it was. With `ObserveActiveChannel` it receives the mesh like any chat channel. Whether `_state` may follow is your call in the switch. Nothing else differs from today's collector, apart from subscribing first.
- G1 and G2 stand as written in the plan.
- Built and ran:
  - The stand-alone `:domain` build. The full `:domain:jvmTest` at `f84bbc9` ran 216 tests (`651f462`'s 206 plus 10), 0 failures, with the Konsist rules. The task line read `> Task :domain:jvmTest`, and no results were left over from the removed test class.
  - Mutations, eight in all. Each was applied alone, `ObserveActiveChannelTest` was run with `--tests`, and the file was restored. Each run left fresh failures, so the task ran. Every one failed at least one test:
    - `ChannelChanged` not matched;
    - a re-read on every event;
    - `distinctUntilChanged` added;
    - no read on start;
    - the read before the subscription, which only the during-the-first-read test catches;
    - Bluetooth off giving no channel;
    - a non-chat state giving the mesh;
    - another blocking state giving the mesh.
- Not built: as before, `:data:repo` (the `LoRaAppInitializer` line), `:presentation:viewmodel` and the apps.

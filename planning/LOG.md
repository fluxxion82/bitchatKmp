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

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

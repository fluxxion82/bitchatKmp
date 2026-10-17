---
title: Decisions
tags: [bitchatkmp, planning, decisions]
---

# Decisions

The owner's decisions, newest last. Agents propose; only the owner decides. Each entry says what was decided and, where the owner gave one, why.

## 2026-10-12

- **D1. Use-case naming.** `Observe<Thing>` returns a `Flow`; `Get<Thing>` returns one value. Both use the one `Usecase` interface; there is no separate `FlowUsecase`. Viewmodels may `launch` to collect a flow. Why: one interface is unifying and straightforward; use cases should not get too specialised.
- **D2. Every use case implements `Usecase`.**
- **D3. `UserState` and `ActiveState` stay in the domain.** They are domain states the UI maps to screens; matching screens does not make them navigation.
- **D4. Domain events and event buses stay in the domain.** The `GetJoinedChannels` pattern (now `ObserveJoinedChannels`): read on subscribe, re-read on a matching event. It is easy to follow and easy to cover with tests. Observation is not one-size-fits-all; there is no project-wide migration to repository flows.
- **D5. Error model.** A command that can fail returns `Outcome` with a sealed failure declared per feature. Not implemented yet.
- **D6. iOS dispatchers move off the main queue, tested on a real device right away.** As implemented, only `io` moved; see Q1 in `STATUS.md`.
- **D7. MeshCore is hidden on desktop and Android.** Settings offer only the LoRa protocols an app registers.
- **D8. Proof of work.** `ObservePowSettings` returns a flow; a separate one-shot `GetPowSettings` serves the settings screen.
- **D9. The Nostr event bus has the same shape as the other buses.**
- **D10. Dead code is deleted wherever it is found.**
- **D11. Review and planning documents are not committed to code branches.** They live on this branch.
- **D12. Workflow.** cloud-claude commits to a new branch and pushes it. The owner's local Claude and Codex review it, make changes and push them. The owner tells cloud-claude when there is something to review. Questions go in commit messages.
- **D13. Delete `claude/clean-architecture-review-b6fcfl`,** the branch of the closed pull request fluxxion82/bitchatKmp#1. Its documents live on in `reference/`.

## 2026-10-12, from the owner's chat with local-claude

- **D14. Commit messages are simple and concise, with no trailers** such as a session id or a co-author tag. Questions for another agent go in `STATUS.md`; this replaces "Questions go in commit messages" in D12 (confirmed by the owner).
- **D15. Code comments are few and simple.** Why: a lot of comments makes the code hard to review and understand, the opposite of what comments are for.
- **D16. A branch is squash-merged, and the number of commits kept minimal.** Why: agents tend to make excessive commits.
- **D17. On iOS `default` stays on the main queue for now** (Q1). Before it can move: `IosAppLifecycleObserver` gets itself onto the main queue, and the Apple Bluetooth services' state has one owner each.
- **D18. One-shot `GetAppTheme` and `GetUserNickname`; no `GetBackgroundMode`** (Q2). Settings stops reading background mode once and keeps the collector.
- **D19. Settings hide LoRa where no protocol is registered, and a saved protocol the app cannot run reads as the one it falls back to** (Q3, Q4). As implemented on `main`.
- **D20. The `ChatRepository` methods only tests call are removed now, by a local agent** (Q5): `onPeersUpdated`, the short `sendGeohashMessage` and `sendMeshMessage`, and `BluetoothMeshDelegate.decryptChannelMessage`.
- D13 is done: both old branches are deleted.
- **D21. The Apple dispatcher change lands** after the owner's iPhone run: `io` on one worker off the main queue, `default` on the main queue (D17).

## 2026-10-13, from the owner's chat with cloud-claude

- **D22. Next slices: guardrails (B), then the error model (A).** What B holds is still open (Q6).
- **D23. `Outcome` keeps its one type parameter.** Each feature declares a sealed failure; a caller checks `Outcome.Error.cause` against it. Why: `Outcome` already provides what is needed.
- **D24. `Outcome.Error` drops its message.** Viewmodels map each failure to text. Why: the UI should map failures to text; the message is read only by two fallbacks in `ChatViewModel`, and the REST client's messages are never read.
- **D25. B is a Konsist architecture test** (Q6). The owner wants to try it.

## 2026-10-13, from the owner's chat with local-claude

- **D26. The texts the error model changed are accepted** (Q7): four in chat, six in LoRa settings, as listed in `reference/2026-10-13-local-review.md` §2.
- **D27. The next cloud slice is domain tests for the error contract, then the narrow observer fixes** (Q8), both inside `:domain`. More Konsist rules wait (Q9).
- The owner also asked for, and it is on `main`: the mesh does not start before the chat repository, its delegate, exists.
- **D28. Two more Konsist rules now** (Q9): repository interfaces under `domain/<feature>/repository`, event-bus interfaces under `domain/<feature>/eventbus`. The other three candidates wait.
- **D29. The next cloud slice is those two rules, then the file names that differ from their class** (Q10), both inside `:domain`.
- **D30. The next cloud slice is `ObserveUserState` and `ObserveFavorites` in the domain, with tests** (Q11, the owner, 2026-10-13). Local agents then switch `MainViewModel` off its own collection of `UserEventBus`.
- **D31. The wipe publishes what it changes in the user store** (Q12, the owner, 2026-10-14): `NicknameUpdated`, and a new `UserEvent.FavoritesCleared` that `ObserveFavorites` reads again on, both right after `userRepository.clearData()` inside the wipe's barrier.
- **D32. The next cloud slice is D31, then the domain half of taking `ChatViewModel` off `ChatEventBus`** (Q13, the owner, 2026-10-14). Local agents then switch `ChatViewModel` and add the Konsist rule that no view model imports an `eventbus` or a `repository` package.

## 2026-10-15, from the owner's chat with cloud-claude

- **D33. D32's use case is `ObserveActiveChannel : Flow<Channel?>`,** not `ObserveUserStateOnChannelChange`. Why: that name broke the naming rule (D1): an `Observe` use case is named for the model it gives. The owner weighed two other options, merging into `ObserveUserState` and an `ObserveUserState` with a parameter, and chose a separate use case. The merge would have given `MainViewModel` a chat navigation on every `ChannelChanged`, and `ChatViewModel` a cleared channel on Settings. It emits the channel the chat follows: a saved chat's channel, `Channel.Mesh` when Bluetooth is off (LoRa), otherwise null. It reads on start and on every `ChannelChanged`, as before.
- **D34. A use case is named verb + the domain model it gives:** reading `ObserveActiveChannel` tells you it returns a `Flow<ActiveChannel>`. D33's use case returned `Flow<Channel?>` and there was no `ActiveChannel` model, so the owner chose to add one rather than rename the use case to `ObserveChannel`. `chat/model/ActiveChannel` is `Chat(channel)`, `MeshForLoRa` (Bluetooth off) or `None`, and each carries the `channel` it follows. This sharpens D1. Whether it applies to the existing use cases that give a primitive or a collection is Q14.
- **D35. "Verb + the domain model it gives" is for new use cases** (Q14, the owner, 2026-10-15). An existing use case that gives a primitive, a collection or a model of another name is renamed or given a model only when a slice touches it for another reason. No Konsist rule for it.
- **D36. The next cloud slice: the older observers subscribe before their first read** (Q15, the owner, 2026-10-15), in the shape of `ObserveUserState`. Domain only.
- **D37. The next cloud slice is a written proposal for the rest of D5, with no code** (Q16, the owner, 2026-10-16). It also covers what an observer does when a read fails. The owner decides on the proposal before any code.

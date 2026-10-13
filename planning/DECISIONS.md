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

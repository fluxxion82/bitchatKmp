---
title: Local review of the seventh slice (D36: older observers subscribe first)
date: 2026-10-16
tags: [bitchatkmp, architecture, review, local]
related:
  - "[[2026-10-15-local-review-d31-d32]]"
---

# Local review of the seventh slice (D36)

- **Who:** local-claude, with Codex (`gpt-6-astra`, high effort): one review of the code, the observer table and the documents, and a second round on the fixes. local-claude built every target.
- **What:** `claude/observers-subscribe-first` at `f5534c8`, one commit on `5bfbd70`, put onto the owner's local `main` at `bbbf892`. That `main` holds two commits not yet pushed, both outside the domain: `43215e8` (`ChatRepo` follows Bluetooth after any user event, the task filed in the last review) and `bbbf892` (a private text no Bluetooth link took is reported as failed).
- **Result:** the branch built and passed, and its 15 observers are right: Codex compared each with the old code, trigger by trigger and branch by branch. Two of the four paths left out did have a window. Both are fixed.

## 1. What was checked first

- With the old observer code under the new tests, exactly 17 tests fail, one per changed path. local-claude ran this itself, not only the cloud's scratch build. All 17 pass on the new code.
- Each during-the-first-read test pins three rules at once: the matching event reads again, the unrelated one does not, and the subscription comes first.
- All targets build and their tests pass (domain 242, view model 97, repository 383).

## 2. What was found

| # | Found by | What | Done |
|---|---|---|---|
| 1 | Codex | **The geohash path of `ObserveChannelMessages` still had the window on the boards.** It sent its trigger, then subscribed; the read runs downstream. On Android, iOS and the desktop the collector runs on `Main.immediate`, and the producer subscribes before the read can start. The boards have no Main dispatcher, so view models run on `Default`. There the read can start on another thread before the subscription. The test written for this path passed on the old code because the test dispatcher runs everything on one thread | The geohash path has the same shape as the other 17. A new test runs the flow on `Default`, holds the producer just before it subscribes, and records whether the subscription was there when the read started. It fails on the old path (3 of 3 runs) and passes on the new one (20 of 20) |
| 2 | Codex, widened by local-claude | **`ObservePowSettings`'s mining status was not a `StateFlow`.** It came from the settings library's `callbackFlow`, which reads first and registers its listener after. The `makeObservable` wrapper takes the listener's baseline at registration, so mining that ended between the two was never shown. Also found: that wrapper keeps its listeners in a plain `mutableMapOf`, which the miner's thread iterates while collectors add and remove listeners. And the flag was stored, so a crash mid-mining showed "mining" in the next run | `LocalNostrPreferences` (a Koin `single`) keeps the flag in a `MutableStateFlow(false)`; `NostrClient` and the wipe are its only writers. The module drops the two settings libraries nothing uses any more. Two tests, both red on the old code: mining that ends right after the first value is shown, and a new instance does not show mining |
| 3 | Codex | The multi-threaded geohash test can miss the old code if no `Default` worker picks up the read within 500 ms | Recorded, not changed. It can never fail on the new code. Nothing can prove that no read started except by waiting |
| 4 | Codex | The comment over each subscription partly says what the code does | Not changed. It is the same line as in the three observers already on `main` |

The other two paths left out stand: `ObserveTorStatus` re-reads on every event of its bus, and `ObserveMeshChannelPeople` treats its first event as "everything changed".

## 3. Corrections to the documents

- **`LOG.md`, D36 plan:** "the mining status is a `StateFlow`" was false. It is one now.
- **`LOG.md`, D36 pushed:** "the geohash test passed on today's shape, so the path is left as it is." A test on a one-thread dispatcher cannot show a race that needs two threads.
- **Counts:** 15 files, 17 paths, 26 tests, 242 in all: right. The same comment appears 17 times, not 15.

## 4. Verification

On the tree that landed, by local-claude on macOS arm64:

- `scripts/verify.sh full`: OK.
- `scripts/tui-smoke.py`: PASS.
- Changes caught by the tests:
  - the old shape on all 17 paths (17 tests failed);
  - the old geohash path;
  - the old mining flag;
  - a mining flag in memory that subscribes after its first value.

The two commits were built together, not one by one.

## 5. The next slices

Recommendation, in order. The owner chooses (Q16).

1. **A written proposal for the rest of D5 (cloud, no build).** For each use case that still lets an exception through: the failure it would return, what the failure carries, what a cancellation does, and the text the view model shows. The same proposal answers what an observer does when a read fails, which this slice and D30 left open.
2. **Model moves with their file names (local build needed).** `UserState` and `ActiveState` to `user/model` (this also renames `AppState.kt`), and `GeoPoint` to its own file.
3. **The two Konsist candidates that wait.** The app parent modules import no UI toolkit; no comma in a backticked test name under `commonTest`. Both read modules the cloud session cannot build.

---
title: Conventions
tags: [bitchatkmp, planning, conventions]
---

# Conventions

The project's working rules, for agents that cannot see the owner's local instruction files: `CLAUDE.md` and `AGENTS.md` are gitignored on purpose, and `docs/` left the repository in `3c46c5c`. The owner's word in chat wins; record a change in `DECISIONS.md`.

## Architecture

- Dependencies point inward: `apps:* → presentation:* → domain` and `apps:* → data:* → domain`. `domain` depends on no project module.
- Repository interfaces live in `domain/<feature>/repository`, implementations in `data:repo`. Event buses are feature-scoped interfaces in `domain/<feature>/eventbus` with in-memory implementations registered in `domainModule` (D4).
- Use cases are small verb-named classes on the one `Usecase<TParam, TResult>` interface. `Observe<Thing>` returns a `Flow`, `Get<Thing>` returns one value (D1, D2).
- Viewmodels take use cases and `presentation:viewvo` state, never repositories. A viewmodel must not have a function named like one of its injected use cases: `observeTorStatus()` then calls the function, not the use case. This broke the first slice.
- `apps/` holds only apps. What a platform's two apps share lives in their parent module (`:apps:desktop`, `:apps:embedded`), which stays free of any UI toolkit.
- Koin modules per layer; the apps are the composition roots. `DesktopModulesTest.desktopGraphVerifies` runs Koin `verify()` over the desktop graph: a definition whose constructor takes something Koin does not provide needs an `injectedParameters` entry there.

## Commits, comments, merges

- A commit message is one short imperative line. Add a body only when the why is not obvious, and keep it to one sentence. No trailers: no `Co-Authored-By`, no session link, no "Generated with" (D14).
- What was and was not verified, and questions for review, go in `LOG.md` and `STATUS.md`, not in the commit.
- Comments are few and short (D15). Say why, where the code cannot. No history ("used to", "now", "as before"), no restating the code, no notes to the reviewer.
- A branch is squash-merged into `main` (D16), so nothing depends on its history. Keep it to a few commits.
- No documents on code branches, and none under `docs/` (D11).

## Verification

- Say in `LOG.md` exactly what was built and run, per module, at which commit.
- The cloud session builds only `:domain`. A change to `data:*`, `presentation:*`, `apps:*` or `iosdi` is unverified until a local agent has run the gates, so keep such changes mechanical and expect fixes on the branch.
- Local gates are the modes of `scripts/verify.sh`: `quick`, `desktop`, `android`, `ios`, `embedded`, `tui`, `desktop-tui`, `full`. Module tests: `:domain:jvmTest`, `:presentation:viewmodel:jvmTest`, `:data:repo:desktopTest`, `:apps:desktop:test`. The terminal UI modules need `-Ptui.enabled=true`.
- A test under `commonTest` also compiles for Kotlin/Native, which refuses a comma in a backticked test name.
- `:domain:jvmTest` and `:presentation:viewmodel:jvmTest` each hold an `ArchitectureTest` (Konsist, D25). A new exception to one of its rules goes into the test's own list, on the owner's word.
- A rule in an `ArchitectureTest` asserts with `kotlin.test` on a list it computed itself; the domain's helper for that is `assertEach`. Konsist's own `assertTrue` and `assertFalse` pass on an empty list, and they leave out a declaration annotated `@Suppress` with the calling function's name. No annotation in the checked code may switch a rule off.
- Konsist reads sources; Gradle compares classes. A module with an `ArchitectureTest` declares its `src` as an input of `jvmTest`, so the rules run again after any source change. A plant that must fail proves a rule only if the task ran: read its outcome (`FROM-CACHE`, `UP-TO-DATE`) before believing a pass, and try the plant without `--rerun` too.
- A scratch build is reported as what it was: the input commits, what was stubbed or left out, and the tasks. It is not the module's own test task.

## Failures

- A command that can fail returns `Outcome`; its failure is a sealed type declared per feature (D5, D23).
- A failure carries facts: which protocol, the exception's message or null. The viewmodel owns every text the user sees (D24), "Unknown error" included.
- A fact a failure carries comes from where the failure happened, never from what the screen has selected: the selection and the running state can differ.
- A fact about state behind a lock is decided under that lock and returned from there. Do not read it again after the call has returned.

## Observers and events

- An observer reads on start and reads again on a matching event (D4).
- It subscribes to the bus before its first read, so an event published during that read is not lost when another takes the single replay slot. `ObserveUserState` and `ObserveFavorites` show how; the older observers still read first.
- Before narrowing which events an observer reads again on, find every writer of what it observes, in every layer, and check that each publishes that event. The publishers of an event are not the writers of the state: a wipe reset the theme without `ThemeUpdated`.
- A test that records behaviour nobody has decided says so in its name ("today"), so it is not read as a rule.
- A test that a publish follows a write uses a fake store that changes only at that write, so a publish made before it fails the test.

## Structure that must survive a refactoring

Nothing on the mesh, on LoRa or from a relay authenticates its sender, and most of what contains that lives in `ChatRepo`. Each item below went through adversarial review. A refactoring may move one only after a written design has been reviewed locally.

- `ChatRepo` has three lock domains. `privateChatsLock`: private chats, unread state, known peers, the stranger budget. `outboxLock`: queued private texts, taken before `privateChatsLock`, never after. `meshChannelMessagesMutex`: the mesh messages and `CrossTransportTwins`. Nothing suspends, launches or publishes inside `privateChatsLock`.
- One writer per state: `nameMeshChatIfUnnamed` for mesh chat names, `trimMeshMessages` for the mesh list, `UserPreferences.updateFavorites` for favourites (one storage step, never a read followed by a save).
- No cached or derived copy of repository state to make a read cheaper. It was tried twice and raced both times.
- Message lists are stored in arrival order and sorted only when read; a trim drops the oldest arrival. Never sort on insert: the sender writes the timestamp.
- Bounds keep their numbers and their tests wherever they move: `MessageLimits` (1,337 messages and 1,000,000 characters per chat; chats the user never wrote in share 5,000 messages and 5,000,000 characters), `LearnedNames` (1,337), favourites recorded for others (200), `BoundedIdSet` (4,096).
- A mesh private chat keeps the name it was opened under. A name a peer announces is a claim, shown beside that name and never instead of it.
- A relay's event is verified in `NostrEventDeduplicator` before any handler sees it, and nothing else records an event id.
- `AppForegroundState` hands every foreground and background edge to its listeners in order. A `StateFlow` alone can drop a short background, and with it a Tor stop.
- Wire formats, persisted keys and Tor's fail-closed behaviour do not change in a refactoring.

---
title: Local review of the fourth slice (D28, D29: placement rules, file names)
date: 2026-10-13
tags: [bitchatkmp, architecture, review, local]
related:
  - "[[2026-10-13-local-review-d27]]"
---

# Local review of the fourth slice (D28, D29)

- **Who:** local-claude, with Codex (`gpt-6-astra`, high effort): one review of the code and of `planning` at `7aed639`, and a second round on the local fixes. local-claude built every target.
- **What:** `claude/konsist-placement-rules` at `d79c235` and `claude/domain-file-names` at `967d59b`, each one commit on `ff4697c`, put onto the owner's `main` at `b572169`. That `main` had three commits the cloud session had not seen (section 5).
- **Result:** both branches built and passed as pushed, and nothing in either needed changing. Two faults came out that are older than the branches and held for every Konsist rule: the build did not run the rules again after some source changes, and a `@Suppress` in the checked code switched a rule off. Both are fixed in a third commit.

## 1. What was found

| # | Found by | What | Done |
|---|---|---|---|
| 1 | local-claude | **Gradle did not run the rules again when a source changed that the JVM build does not turn into different classes.** Konsist reads the module's `src` from disk. The inputs of `:domain:jvmTest` were the compiled JVM classes. A `ProbeRepository` planted in `linuxMain` gave `:domain:jvmTest FROM-CACHE` and a green build; with `--rerun` the rule failed. The same goes for `appleMain`, and for a change in `commonMain` that compiles to the same bytecode: a `@Suppress` added to a planted interface left the task up to date | `domain/build.gradle.kts` and `presentation/viewmodel/build.gradle.kts` declare `src` as an input of `jvmTest`. Plants in `linuxMain`, `appleMain` and the viewmodel's `iosMain` now fail without `--rerun`, and the clean tree is served from the cache again |
| 2 | Codex, widened by local-claude | **A `@Suppress` in production code switched a rule off.** Konsist's `assertTrue` and `assertFalse` leave out a declaration annotated `@Suppress` with the name of the function that calls the assertion. It works with or without a `konsist.` prefix, on the declaration or on the file. `@Suppress("assertPlacedIn")` on a misplaced interface passed both new rules. The three older domain rules and the viewmodel rule had the same way out under their own names. The non-empty check runs before the declaration is left out, so it did not help | Neither test uses a Konsist assertion any more. The domain test has one helper, `assertEach`: it asserts that the rule selected something and that nothing selected breaks it, and names each offender with its file and line. The viewmodel test does the same inline. Suppressed plants now fail (section 2) |

Why the cloud session could not see row 1: its scratch build has no build cache, and each of its plants followed a failed run, which Gradle always runs again.

Why row 2 matters here: `CONVENTIONS.md` says an exception to a rule goes into the test's own list, on the owner's word. An annotation in the checked code was a second, unlisted way.

## 2. What was planted

Each was written alone, the test task was run without `--rerun`, and the file was removed.

Before the fixes, on the branches as pushed (with `--rerun` where the cache would have answered):

| Plant | Result |
|---|---|
| `ProbeRepository` in `chat.model` | repository rule fails |
| `ProbeEventBus` in `base.eventbus` | bus rule fails |
| A class in `chat.model` with a nested `InnerEventBus` | bus rule fails |
| `fun interface ProbeEventBus` in `chat` | bus rule fails |
| `ProbeRepository` in `chat.repository.sub` | repository rule fails |
| `ProbeRepository` in `com.bitchat.domain.repository` | repository rule fails |
| `tor.eventbus.ProbeEventBus` and `lora.repository.ProbeRepository` | both rules pass |
| The suffix `EventBus` changed to `EventBuses` | bus rule fails on its non-empty assertion |
| `ProbeRepository` in `linuxMain`, package `tor` | **passed from the cache**; failed with `--rerun` |
| `@Suppress("konsist.assertPlacedIn")`, `@Suppress("assertPlacedIn")` or the same at file level on a misplaced `ProbeRepository` | **passed** |

After the fixes, each of these fails the rule it should:

- a misplaced `ProbeRepository` with `@Suppress("assertPlacedIn")`, and one with `@Suppress("assertEach")`;
- a misplaced `ProbeEventBus` under `@file:Suppress("konsist.assertPlacedIn")`;
- `class GetBogus : Usecase<Unit, Flow<Int>>` suppressed under the `Observe` rule's name;
- a plain public class in `chat` suppressed under the use-case rule's name;
- a `java.io.File` import in `jvmMain` suppressed under the import rule's name;
- `ProbeViewModel` with a function named like its constructor parameter, suppressed, in `commonMain` and unsuppressed in `iosMain`;
- `ProbeRepository` in `linuxMain` and `ProbeEventBus` in `appleMain`;
- the suffix `Repository` changed to `Repositories` ("The rule selected nothing.").

The correctly placed pair and the clean tree pass.

## 3. The rules and the renames, as checked

- On the merged tree the rules select 9 repository interfaces (App, BlockList, Chat, Connectivity, LoRaSettings, Location, Nostr, Tor, User) and 7 bus interfaces (App, Chat, Connection, Location, Nostr, Tor, User), all in `commonMain` and all in place.
- Selected and checked whatever their form: `fun interface`, `expect interface`, a nested interface, `internal` and `private` ones, and one in a file with no package line (it fails). The nested and `fun interface` forms were planted; the others are Codex's reading of Konsist's code.
- Left out on purpose, by D28's wording: a port not named `*Repository` or `*EventBus`, a typealias, a class with such a name (the six `InMemory*EventBus` classes are implementations), and whether a contract belongs to the feature it sits in.
- The three renamed files are the same blobs before and after. The old `AppInformation.kt` is the new one followed by `Version.kt` without its package line.
- None of the five files has a top-level function, property or typealias, so no JVM facade class and no Swift name changes. Nothing in the code tree names the old files: Gradle, scripts, Swift, `iosdi`, the Xcode project.
- `Channel` keeps its serial names; they come from the type and its `@SerialName`s.
- `location/model/LocationEvent.kt` still holds `LocationEvent`, the location bus's event. The rename ends two files of that name.
- What does change: the file name in debug information and stack traces.

## 4. Corrections to the documents

- **`LOG.md`, "Each misplaced plant failed only its own rule", for the `appleMain` plant.** True in the scratch build. In the repository's build it passed from the cache until row 1 was fixed.
- **`LOG.md`, "Git cannot follow a split."** The diff shows `M AppInformation.kt` and `A Version.kt`, but `git log --follow` does trace `Version.kt` back by similarity.
- **`LOG.md`, "Nothing in the repository names these files."** Nothing in the code tree. The notes on `planning` do, and need no change.
- **Test counts.** 179 and 181 are for `ff4697c`. On today's `main` `:domain:jvmTest` has 183: `b572169` added two tests.
- **`STATUS.md`** named `ff4697c` as `main`. It is rewritten.

## 5. What `main` gained that the cloud session has not seen

- `c923790`: the REST client closes a retired client's engine. No domain change.
- `e1416cc`: string resources each on one line. No domain change.
- `b572169`, the default place. In the domain:
  - `LocationRepository.getAvailableGeohashChannels()` returns `NearbyChannels(channels, fix)`, and so does `GetAvailableChannels`. `LocationRepository.getLastFixInfo()` and the use case `GetLastFixInfo` are gone, with its Koin line.
  - New in `location/model`: `NearbyChannels`, `DefaultPlace.kt` (`DEFAULT_PLACE`, `DEFAULT_PLACE_NAME`), `LocationFixInfo.Source.DEFAULT` and `LocationFixInfo.unavailableReason`.
  - `GetAvailableChannelsTest` has two more tests.

## 6. File names that still differ from their type

D29 fixed its list at four, so none of these was done.

- **`app/model/AppState.kt`** holds `UserState` and `ActiveState`; `AppState` itself was deleted in the first slice. The plan moves both types to `user/model`. Rename the file with that move, not before.
- `CoroutinesContextFacade.apple.kt`, `.jvm.kt` and `.linux.kt` hold `actual class DefaultContextFacade`. That is the usual naming of platform files. Leave them.
- `base/LogRedaction.kt` is a file of functions with one small type, `LogPolicy`. Leave it.
- Files with more than one type: `chat/ChatNotices.kt`, `chat/ProcessChatCommand.kt`, `chat/model/ChannelMember.kt`, `chat/model/MeshChannelPerson.kt`, `location/model/GeohashChannel.kt` (with `GeoPoint`), `user/model/BlockedUser.kt`, `tor/RequestedTorIntent.kt`, `lora/model/LoRaSettings.kt` (five types), `base/CoroutineScopeFacade.kt`. Most pair a type with its helper. `GeoPoint` and the LoRa settings types are the ones worth their own files.

## 7. The next slices

Recommendation, in order. The owner chooses (Q11).

1. **`ObserveUserState` and `ObserveFavorites` (cloud for the domain half, local for the rest).** `MainViewModel` collects `UserEventBus` itself and `ChatViewModel` collects `ChatEventBus`; these are the two places a viewmodel imports a bus. The cloud session writes the two use cases with tests and their Koin lines, after listing every writer of the state each observes. Local agents then switch `MainViewModel` over and run the desktop Koin check and the app builds.
2. **A written proposal for the rest of D5 (cloud, no build).** For each use case that still lets an exception through: the failure it would return, what the failure carries, what a cancellation does, and the text the viewmodel would show. The owner decides on the proposal before any code.
3. **Model moves with their file names (cloud writes, local builds).** `UserState` and `ActiveState` to `user/model` in a file named for them, `GeoPoint` to its own file. This changes imports in the viewmodel, data and app modules, which only a local build checks.
4. **The three Konsist candidates that wait (D28).** They read modules the cloud session cannot build.

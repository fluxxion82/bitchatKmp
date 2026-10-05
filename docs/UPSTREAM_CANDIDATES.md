# Upstream Candidates

This document tracks changes carried in local forks and bugs found in upstream code. The owner decides what to propose and opens every issue or pull request. Agents may prepare a clean single-purpose branch, never a pull request.

Started 2026-10-02 during the Kotlin `2.5.0-Beta1` fork refresh. Final fork commits: Compose Multiplatform Core `5e437f50092`, Compose Multiplatform `d246a3b8a5`, Koin `e491a64b`, Mosaic `bb346538`.

**Kind**: `PR` means a candidate change exists, `issue` means a problem has no proposed fix, and `done` means upstream already contains it. **Fit** estimates upstream interest.

## Mosaic (JakeWharton/mosaic) — `fluxxion82/mosaic` `embedded` at `bb346538`

| Kind | Candidate | Where in the fork | Fit | Notes |
|---|---|---|---|---|
| done | ArcSpline `binarySearch` on native | merged upstream as #1219 | — | dropped during 2026-10 rebase |
| done | `nanoTime` on Linux and Windows | merged upstream as #1215 | — | dropped during 2026-10 rebase |
| PR | Idle-frame suppression | `80718544`; prepared branch `sa.idle-frames.2026-10-01` | high | matters on slow CPUs |
| PR | Retry interrupted reads and writes on `EINTR` | prepared branch `sa.retry-eintr.2026-09-30` | high | |
| PR | Parse `CSI Z` as Shift+Tab | `31ec507b`; prepared branch `sa.shift-tab.2026-10-01` | high | |
| PR | Non-ASCII key parsing fix | `9dc77dfa` | high | remainder after upstream took binary search and nanoTime |
| PR | Terminal correctness: signal-safe shutdown/restore, descriptor lifetime, close-on-exec, serialized writes, partial writes, bounded shutdown, read timeout | parts of `10d80342` | high–medium | split by existing `:mosaic-tty-terminal` tests |
| PR | Linux console F1–F5, cursor hiding without DECRQM reply, tty frame output | parts of `10d80342` | medium | |
| PR | Wide/zero-width layout, canvas clipping, OSC 8 hyperlinks | `ddf0fd37`, `4227156d`, `8d0d2e4c` | medium | independent of wasmJs |
| PR | wasmJs target, browser/html modules, full-screen render mode | `ccd1aaae`, `c5a566c3`, `1f6acf4e` | low–medium | discuss with maintainer first |
| PR | wasmJs ArcSpline actuals | `3c532d14` | medium | meaningful with wasmJs target |
| PR | Burst `2.13.0` to `2.14.0` | `3e96796e` | high | fixes `IrGenerationExtensionException` in `InterceptorInjector` during `mosaic-tty` JVM tests |
| PR | ArcSpline empty-array case | `2e742093` | medium | `ArcSplineTest` needed explicit empty-array coverage |
| issue | Intercepted stdout/stderr fixtures do not call `testFunction` | `mosaic-tty/src/commonTest/kotlin/com/jakewharton/mosaic/tty/DataPipes.kt:252,280` | medium | calling it makes JNI intercepted-output reads block |

## Koin (InsertKoinIO/koin) — `fluxxion82/koin` `sa_linux_4.2.2-kotlin-2.5` at `e491a64b`

| Kind | Candidate | Where in the fork | Fit | Notes |
|---|---|---|---|---|
| PR | `linuxArm64` for `koin-core-viewmodel` | `b8559a3f` | high | pure Kotlin on JB lifecycle |
| PR | wasmJs `KoinPlatformCoroutinesTools.runBlocking` should throw rather than copy JS `getCompleted()` actual | `b8559a3f` | high | correctness bug; issue first |
| PR | Kotlin 2.5 target readiness | `b8559a3f` | high, later | removes targets unavailable in Kotlin 2.5 |
| issue | Gradle `8.13` wrapper / benchmark `macosX64` | `projects/core/benchmark/build.gradle.kts:38` | medium | upstream configuration breaks on Kotlin 2.5 |
| issue | Top-level DSL marker warning | `projects/core/koin-core/src/commonMain/kotlin/org/koin/core/context/DefaultContextExt.kt:34` | low | Kotlin 2.5 warning |
| check | Hardcoded `kotlin-stdlib*` forces and `kotlin-stdlib-common:2.2.10` | `projects/build.gradle.kts`; `compose/koin-compose/build.gradle.kts` | check | confirm upstream ownership first |
| blocked | `linuxArm64` for `koin-compose` / `koin-compose-viewmodel` | `b8559a3f` | blocked | needs upstream Compose UI linuxArm64 |

## Compose Multiplatform Core — `fluxxion82/compose-multiplatform-core` `linux-1.12.1` at `5e437f50092`

| Kind | Candidate | Where in the fork | Fit | Notes |
|---|---|---|---|---|
| issue | Linux/Native target for Compose UI | `460352e37a2`, `d1ac5634bf3` | low | discussion material; no upstream Linux/Native UI target |
| issue | `Dispatchers.Main` fallback on Linux native | `4c3d3ecf4a0` | low | tied to Linux target |
| PR | Treat `*-SNAPSHOT` as snapshot in version classifier | `buildSrc/public/src/main/kotlin/androidx/build/Version.kt:55`, `2d765d8dbac` | medium | permits `1.12.1-embedded-SNAPSHOT` verification |
| issue | `Synchronization.skiko.kt` inline-contract diagnostics | `compose/ui/ui/src/skikoMain/kotlin/androidx/compose/ui/platform/Synchronization.skiko.kt:33` | low | Kotlin 2.5 |
| issue | Missing `@ParameterName` diagnostics in macOS navigation transitions | `navigation/navigation-compose/src/macosMain/kotlin/androidx/navigation/compose/DefaultNavTransitions.macos.kt:26` | low | mirrored by Linux actual |
| PR | Kotlin 2.5 build readiness | `5e437f50092` | medium, later | language/API floors, ABI constructor change, removed watchosArm32 |
| issue | Published `.module` files advertise unpublished variants | build scripts | low | partial publishers cannot disable Apple groups without configuration failure |
| issue | Kotlin 2.5 future-error warnings | `CarouselState.kt:99`; `LegacyRenderNodeLayer.skiko.kt:389` | medium | expression-body return and future-error warning |

## Compose Multiplatform — `fluxxion82/compose-multiplatform` `linux-1.12.1` at `d246a3b8a5`

| Kind | Candidate | Where in the fork | Fit | Notes |
|---|---|---|---|---|
| issue | Release tag retains stale Compose defaults | upstream `v1.12.1` | medium | plugin source build uses `compose.version=1.10.1`, `compose.material3.version=1.9.0` |
| issue | Released plugin Material3 accessor resolves old Material3 | Maven Central `compose-gradle-plugin:1.12.1` | medium | accessor resolves `1.9.0` |
| PR | Non-Darwin resources: XML parser, `/proc/self/exe` reader, Linux sync task | `fdb56b46da` | low–medium | XML parser separable |
| issue | Components Gradle wrapper 8.13 rejected by KGP 2.5 | `components/gradle/wrapper/gradle-wrapper.properties:3`, `e8003428fa` | medium, later | requires 8.14 or newer |
| issue | `androidInstrumentedTest` source-set dependency warning | `components/resources/library/build.gradle.kts:164` | low | |
| issue | Shared metadata sees unresolved `ExperimentalForeignApi` marker | `components/resources/library/build.gradle.kts:90` | low | |
| issue | JetBrains redirect stub jars share names with AndroidX jars | `lifecycle-viewmodel-desktop-2.11.0.jar` example | high | JVM application-plugin `installDist` can overwrite real AndroidX jar, causing `NoClassDefFoundError`; the terminal UI preserves both artifacts under coordinate-prefixed names |

## Kotlin (JetBrains/kotlin)

| Kind | Candidate | Where | Fit | Notes |
|---|---|---|---|---|
| done | KT-88544 `ComputeTypes` miscompile | fixed by `7c0917898`; Kotlin `2.5.0-Beta1` | — | Apple and embedded workarounds removed; canaries pass |
| issue | Kotlin/Native composable fun-interface SAM conversion across klibs raises `IrLinkageError` | core `TextFieldDefaults.kt` | medium | `TextFieldDecorator.Decoration`; worked around in core fork |
| note | `macosX64` removed with a "deprecated" error | KGP `2.5.0-Beta1` | low | distribution has no `macos_x64` klibs |

## Skiko (JetBrains/skiko)

| Kind | Candidate | Where | Fit | Notes |
|---|---|---|---|---|
| done | EGL on linuxArm64 | upstream since `0.9.47` | — | `makeEGL()` fork retired |

## Out of Scope

| Library | Kind | Candidate | Where | Fit |
|---|---|---|---|---|
| gattlib | PR | NULL guards, `g_clear_error`, proxy unref on skip paths | `fluxxion82/gattlib` `bitchat-null-guards` `f647d326` | high |
| MeshCore | PR | Linux companion exit on initialization failure | `fluxxion82/MeshCore` `orangepi-zero3-sx1276` `cb9499e` | medium |
| Meshtastic firmware | PR | RF95 Portduino init/reconfigure hardening | `fluxxion82/firmware` `orangepi-rfm95w` | medium |

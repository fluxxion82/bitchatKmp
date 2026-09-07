# Session 1 Re-entry Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (or subagent-driven-development in-session) to implement this plan task-by-task.

**Goal:** Make bitchatKmp buildable again on any machine (desktop compiles, embedded is opt-in), add a repeatable verification gate, and bring the repo docs back in line with reality.

**Architecture:** No product code changes. Gradle configuration (root `build.gradle.kts`, `gradle.properties`, `apps/desktop/build.gradle.kts`), two shell scripts under `scripts/`, and documentation (`CLAUDE.md`, `README.md`, `docs/FORKED_LIBRARIES.md`, embedded docs). Every task ends with a green Gradle invocation and a commit on branch `reentry/session-1`.

**Tech Stack:** Gradle 9.2.1 (wrapper), Kotlin 2.2.10, Compose Multiplatform 1.10.0, Kotlin/Native linuxArm64 (opt-in), bash.

**Context you need:**
- Repo: `/Users/fluxxion/Development/workspace/multiplatform/bitchat/bitchatKmp`. Branch: `reentry/session-1`.
- Background findings: `../docs/plans/2026-09-06-reentry-plan.md` (sections 1 and 4) and `../docs/reviews/2026-09-06-codex-plan-critique.md` (sections 2–4). Read them if a step is unclear.
- `embedded.enabled=true` in `gradle.properties` makes every build depend on this machine's `~/.m2` fork snapshots (`9999.0.0-SNAPSHOT` Compose/lifecycle/savedstate, Koin `4.1.2`, Skiko `0.9.37.3-SNAPSHOT`). Those artifacts ARE present on this Mac, so `-Pembedded.enabled=true` builds still work here.
- Desktop does not compile today because root `build.gradle.kts:26-28` excludes `io.github.kevinnzou:compose-webview-multiplatform` from every configuration in every subproject, while `presentation/design/src/desktopMain/kotlin/com/bitchat/design/{di/KcefAppInitializer.kt,location/MapPickerLauncher.desktop.kt}` import it.
- Always pass `--console=plain` to Gradle. Builds are slow (30 s to several minutes); use generous timeouts. Never run `./gradlew clean` (it throws away native artifacts that take hours to rebuild).
- Commit messages: short imperative summary, body explaining why, and end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Do not touch `../forks`, `~/.m2`, submodules, or anything under `native/`.

---

### Task 1: Make the embedded profile opt-in (plan item T2)

**Files:**
- Modify: `gradle.properties:31`
- Modify: `README.md:109`, `README.md:157`
- Modify: `apps/embedded/README.md:73`, `apps/embedded/README.md:76`
- Modify: `apps/embedded/EMBEDDED_NOTES.md:146`
- Modify: `docs/FORKED_LIBRARIES.md:270`
- Modify: `docs/meshtastic-orangepi-setup.md:126`, `docs/meshcore-orangepi-setup.md:217`
- Check/modify: `scripts/build-all-linux.sh`, `scripts/build-all-platforms.sh` (any `linkDebugExecutableLinuxArm64` / `linkReleaseExecutableLinuxArm64` invocation must pass `-Pembedded.enabled=true`)

**Step 1: Record the current behaviour**

Run: `./gradlew projects --console=plain 2>/dev/null | grep -E "apps:(embedded|desktop)"`
Expected: both `:apps:desktop` and `:apps:embedded` listed (embedded is on today).

**Step 2: Flip the default**

In `gradle.properties` replace line 31 `embedded.enabled=true` with:

```properties
# Embedded (Orange Pi, linuxArm64) profile is opt-in. It swaps Compose/Koin/lifecycle for
# locally published forks (see docs/FORKED_LIBRARIES.md) and includes :apps:embedded.
# Enable per invocation:   ./gradlew -Pembedded.enabled=true :apps:embedded:linkDebugExecutableLinuxArm64
# or for this machine only: put embedded.enabled=true in ~/.gradle/gradle.properties
embedded.enabled=false
```

Leave lines 32–34 (`embedded.composeForkVersion`, `embedded.skikoForkVersion`, `embedded.koinForkVersion`) as they are.

**Step 3: Verify the flag is off by default and on with -P**

Run: `./gradlew projects --console=plain 2>/dev/null | grep -E "apps:(embedded|desktop)"`
Expected: only `:apps:desktop`.

Run: `./gradlew -Pembedded.enabled=true projects --console=plain 2>/dev/null | grep -E "apps:(embedded|desktop)"`
Expected: both listed.

Run: `./gradlew :apps:desktop:dependencies --configuration runtimeClasspath --console=plain 2>/dev/null | grep -m1 -E "org.jetbrains.compose.ui:ui-desktop|io.insert-koin:koin-core"`
Expected: versions `1.10.0` / `4.1.1` (Maven), not `9999.0.0-SNAPSHOT` / `4.1.2`.

Run: `./gradlew :domain:jvmTest --console=plain 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL` (28 tests, 2 skipped).

**Step 4: Update every documented embedded command**

Each of the listed doc lines shows a Gradle command for the embedded target. Prefix the flag, e.g. change

```
./gradlew :apps:embedded:linkDebugExecutableLinuxArm64
```
to
```
./gradlew -Pembedded.enabled=true :apps:embedded:linkDebugExecutableLinuxArm64
```

`README.md:109` currently says `Requires embedded.enabled=true in gradle.properties`; change it to `Requires -Pembedded.enabled=true (or embedded.enabled=true in ~/.gradle/gradle.properties)`.

Grep to make sure nothing is missed: `grep -rn "linkDebugExecutableLinuxArm64\|linkReleaseExecutableLinuxArm64\|embedded.enabled" --include="*.md" --include="*.sh" . | grep -v "/build/"` — every Gradle invocation of an embedded task must carry the flag, and no doc may still say the flag lives in the repo's `gradle.properties`.

For `scripts/build-all-linux.sh` and `scripts/build-all-platforms.sh`: if they invoke embedded link tasks, add `-Pembedded.enabled=true` to those `./gradlew` calls. Do not restructure the scripts.

**Step 5: Commit**

```bash
git add gradle.properties README.md apps/embedded/README.md apps/embedded/EMBEDDED_NOTES.md docs/FORKED_LIBRARIES.md docs/meshtastic-orangepi-setup.md docs/meshcore-orangepi-setup.md scripts/build-all-linux.sh scripts/build-all-platforms.sh
git commit -m "Make the embedded linuxArm64 profile opt-in

embedded.enabled=true was committed, which made every build (desktop,
Android, iOS) resolve Compose, lifecycle, savedstate and Koin from this
machine's ~/.m2 fork snapshots and put mavenLocal first. Default it to
false so a fresh checkout builds from Maven Central; embedded builds pass
-Pembedded.enabled=true. Docs and scripts updated to match.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: Restore the desktop build and fix packaging icon paths (plan item T1)

> **Superseded in part (commit fbc91ab):** review showed the webview is imported only by `desktopMain` (Android uses `android.webkit.WebView`, iOS uses `WKWebView`) and never reaches any linuxArm64/native/common configuration. The final state deletes the `androidMain`/`iosMain` declarations in `presentation/design/build.gradle.kts` and removes the root exclude entirely instead of guarding it. Steps 2–4 below describe the intermediate guarded form for the record.

**Files:**
- Modify: `build.gradle.kts:26-28`
- Modify: `apps/desktop/build.gradle.kts:42`, `:53`, `:56`

**Step 1: Reproduce the failure**

Run: `./gradlew :apps:desktop:compileKotlin --console=plain 2>&1 | grep -E "^e: |BUILD" | head -5`
Expected: `e: .../KcefAppInitializer.kt:5:8 Unresolved reference 'dev'.`, `e: .../MapPickerLauncher.desktop.kt:32:12 Unresolved reference 'multiplatform'.`, `BUILD FAILED`.

**Step 2: Scope the exclusion to linuxArm64 configurations**

In `build.gradle.kts` replace

```kotlin
    configurations.all {
        // Exclude webview - not supported on linuxArm64
        exclude(group = "io.github.kevinnzou", module = "compose-webview-multiplatform")
```

with

```kotlin
    configurations.all {
        // compose-webview-multiplatform (KCEF) publishes no linuxArm64 artifacts. Keep it out of
        // the Kotlin/Native embedded resolution only; desktop/android/ios declare it per source
        // set in presentation/design/build.gradle.kts and need it to compile.
        if (name.contains("linuxArm64", ignoreCase = true)) {
            exclude(group = "io.github.kevinnzou", module = "compose-webview-multiplatform")
        }
```

**Step 3: Verify desktop compiles**

Run: `./gradlew :apps:desktop:compileKotlin --console=plain 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`.

**Step 4: Verify the embedded profile still resolves without the webview**

Run: `./gradlew -Pembedded.enabled=true :presentation:design:compileKotlinLinuxArm64 --console=plain 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`. If it fails, quote the first `e:` line and check whether it mentions `kevinnzou`/`webview`/`kcef`: if yes, the scoping is wrong (fix it); if it is an unrelated pre-existing native/cinterop error, report it verbatim and continue.

**Step 5: Fix the packaging icon paths**

The files are `apps/desktop/src/main/resources/ic_launcher.icns`, `ic_launcher.ico`, `ic_launcher.png` (there is no `icons/` directory). Change the three `iconFile.set(...)` lines:

```kotlin
iconFile.set(project.file("src/main/resources/ic_launcher.icns"))   // macOS
iconFile.set(project.file("src/main/resources/ic_launcher.ico"))    // windows
iconFile.set(project.file("src/main/resources/ic_launcher.png"))    // linux
```

**Step 6: Launch the desktop app headlessly to catch startup crashes**

Run (this opens a window for ~60 s and is then killed; the `--no-daemon` flag makes the kill take the app down too):

```bash
timeout 75 ./gradlew --no-daemon :apps:desktop:run --console=plain > /tmp/bitchat-desktop-run.log 2>&1; echo "exit=$?"
grep -nEi "exception|error:|UnsatisfiedLink|FAILED" /tmp/bitchat-desktop-run.log | head -10
```

Expected: exit 124 (killed by timeout) or 0, and no stack traces. `libarti_desktop.dylib` exists on this Mac so Tor should initialise. If a stack trace appears, quote its first three lines in your report; do not attempt to fix product code in this task.

**Step 7: Verify packaging picks up the icons**

Run: `./gradlew :apps:desktop:packageDmg --console=plain 2>&1 | tail -3` (5–10 minutes; jpackage)
Expected: `BUILD SUCCESSFUL` and `ls apps/desktop/build/compose/binaries/main/dmg/` shows `bitchat-1.0.0.dmg`. If it fails for a reason unrelated to icons (signing, jpackage missing), quote the error and continue.

**Step 8: Commit**

```bash
git add build.gradle.kts apps/desktop/build.gradle.kts
git commit -m "Fix desktop build: scope webview exclude to linuxArm64, fix icon paths

The global exclude of compose-webview-multiplatform (added with the
embedded target) removed KCEF from the desktop classpath while the
desktop source set still imports it, so :apps:desktop has not compiled
since 28f56be. Only Kotlin/Native linuxArm64 configurations need the
exclusion. Also point nativeDistributions at the icon files that
actually exist under src/main/resources.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: Verification script and baseline manifest (plan item T0)

**Files:**
- Create: `scripts/verify.sh`
- Create: `scripts/baseline-manifest.sh`
- Create (generated): `docs/baseline/2026-09-06.md`

**Step 1: Write `scripts/verify.sh`**

```bash
#!/usr/bin/env bash
# Verification gates for bitchatKmp.
#
#   scripts/verify.sh            # quick: domain tests + desktop compile
#   scripts/verify.sh desktop    # quick + packageDmg
#   scripts/verify.sh android    # :apps:droid:assembleDebug
#   scripts/verify.sh ios        # :iosdi:linkDebugFrameworkIosSimulatorArm64
#   scripts/verify.sh embedded   # -Pembedded.enabled=true linuxArm64 link + compose resources
#   scripts/verify.sh full       # all of the above (desktop packaging included)
#
# Env: WARN=1 adds --warning-mode all; GRADLE_ARGS adds arbitrary flags.
# GRADLE_ARGS is word-split; values containing spaces are not supported.
#
# Packaging on a Homebrew JDK: Compose's packageDmg refuses Homebrew JDKs
# (compose-multiplatform#3107) because the bundle may depend on Homebrew libraries
# absent on other Macs. Prefer a Corretto/Temurin JDK 21 (JAVA_HOME). To build a
# local-only dmg anyway, opt out explicitly:
#   GRADLE_ARGS='-Pcompose.desktop.packaging.checkJdkVendor=false' scripts/verify.sh desktop
set -euo pipefail
SELF="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
cd "$(dirname "$0")/.."

MODE="${1:-quick}"
# Non-embedded modes pin the flag off so a ~/.gradle/gradle.properties override cannot
# silently change what is being verified.
BASE=(--console=plain ${GRADLE_ARGS:-})
[[ "${WARN:-0}" == "1" ]] && BASE+=(--warning-mode all)

gradle()          { echo "== ./gradlew ${BASE[*]} -Pembedded.enabled=false $*"; ./gradlew "${BASE[@]}" -Pembedded.enabled=false "$@"; }
gradle_embedded() { echo "== ./gradlew ${BASE[*]} -Pembedded.enabled=true $*";  ./gradlew "${BASE[@]}" -Pembedded.enabled=true "$@"; }

case "$MODE" in
  quick)    gradle :domain:jvmTest :apps:desktop:compileKotlin ;;
  desktop)  gradle :domain:jvmTest :apps:desktop:compileKotlin :apps:desktop:packageDmg ;;
  android)  gradle :apps:droid:assembleDebug ;;
  ios)      gradle :iosdi:linkDebugFrameworkIosSimulatorArm64 ;;
  embedded) gradle_embedded :apps:embedded:linkDebugExecutableLinuxArm64 ;;
  full)     for m in desktop android ios embedded; do "$SELF" "$m"; done ;;
  *) echo "usage: $0 [quick|desktop|android|ios|embedded|full]" >&2; exit 2 ;;
esac
echo "verify.sh $MODE: OK"
```

Embedded resources: the `compose-resources/` copy is done by the forked Compose Gradle plugin (`../forks/compose-multiplatform/gradle-plugins/compose/src/main/kotlin/org/jetbrains/compose/resources/LinuxResources.kt`) and is already a dependency of the link task, so nothing extra is needed in the `embedded` case. (An earlier draft of this step said to look in `apps/embedded/build.gradle.kts`; there is nothing there.)

`chmod +x scripts/verify.sh`.

**Step 2: Run the quick gate**

Run: `scripts/verify.sh`
Expected: `BUILD SUCCESSFUL` then `verify.sh quick: OK`.

**Step 3: Write `scripts/baseline-manifest.sh`**

```bash
#!/usr/bin/env bash
# Snapshot everything a fork-dependent build relies on, so upgrades can be diffed and rolled back.
# Output: docs/baseline/<date>.md (commit it).
set -euo pipefail
cd "$(dirname "$0")/.."
DATE="${1:-$(date +%F)}"
OUT="docs/baseline/${DATE}.md"
mkdir -p docs/baseline
M2="${HOME}/.m2/repository"

{
  echo "# Build baseline ${DATE}"
  echo
  echo "Generated by \`scripts/baseline-manifest.sh\` on $(uname -m)."
  echo
  echo "## Repo"
  echo '```'
  echo "HEAD $(git rev-parse --short HEAD) $(git branch --show-current) dirty=$(git status --porcelain --ignore-submodules=dirty | grep -vc '^??' || true)"
  git status --porcelain --ignore-submodules=dirty | grep -v '^??' || true
  git submodule status
  echo '```'
  echo
  echo "## Toolchain"
  echo '```'
  grep distributionUrl gradle/wrapper/gradle-wrapper.properties
  sed -n '/^\[versions\]/,/^\[libraries\]/p' gradle/libs.versions.toml | grep -E '^(kotlin|agp|ksp|coroutines|ktor|koin) = '
  grep -E '^embedded\.' gradle.properties
  java -version 2>&1 | head -1
  brew list --versions libsodium secp256k1 2>/dev/null || true
  echo '```'
  echo
  echo "## Forks (../forks)"
  echo '```'
  for d in ../forks/*/ ../forks/jake/*/; do
    [ -d "$d/.git" ] || [ -f "$d/.git" ] || continue
    printf '%-40s %s %s dirty=%s\n' "$d" "$(git -C "$d" rev-parse --short HEAD)" "$(git -C "$d" branch --show-current)" "$(git -C "$d" status --porcelain | grep -vc '^??' || true)"
  done
  echo '```'
  echo
  echo "## Local Maven artifacts (fork snapshots; sources/javadoc and non-Linux Koin variants omitted)"
  echo '```'
  # Each find/grep stage is guarded so a missing directory or an empty result on a fresh
  # machine cannot abort this block under pipefail and leave a truncated document.
  { find "$M2/org/jetbrains/compose" "$M2/org/jetbrains/androidx" "$M2/org/jetbrains/skiko" "$M2/io/insert-koin" \
         -type f \( -name '*.jar' -o -name '*.klib' -o -name '*.module' \) \
         -not -name '*-sources.jar' -not -name '*-javadoc.jar' \
         \( -path '*9999.0.0-SNAPSHOT*' -o -path '*0.9.37.3*' -o -path '*/4.1.2/*' \) 2>/dev/null || true; } \
    | { grep -vE '/koin-[^/]+-(watchos|tvos|js|wasm|mingw|ios)[^/]*/' || true; } \
    | sort | while read -r f; do printf '%s  %s\n' "$(shasum -a 256 "$f" | cut -c1-16)" "${f#$M2/}"; done
  echo '```'
  echo
  echo "## Prebuilt native artifacts consumed by the build (gitignored)"
  echo '```'
  # The build files link against: native/<lib>/build/<arch>/lib/*.a (libsodium, secp256k1, noise-c),
  # native/libs/<arch>/lib/*.a and native/libs/desktop/*.dylib (arti), gattlib's build/<arch>/install/lib,
  # the hand-built systemd stub archive native/stubs/libsystemd_stubs.a (linked via gattlib.def),
  # and jniLibs/<abi>/*.so (Android arti). Intermediate outputs (.libs/, src/, cargo target/) and
  # sysroots are deliberately excluded.
  # macOS-only: stat -f
  { find data \( -path '*/native/*/build/*/lib/lib*' -o -path '*/native/*/build/*/install/lib/lib*' \
                 -o -path '*/native/libs/*/lib/lib*' -o -path '*/native/libs/desktop/lib*' \
                 -o -path '*/native/stubs/lib*' -o -path '*/jniLibs/*/lib*' \) \
         -type f \( -name '*.a' -o -name '*.so' -o -name '*.dylib' \) -not -path '*/sysroot/*' 2>/dev/null || true; } \
    | sort | while read -r f; do printf '%s  %s  %s\n' "$(shasum -a 256 "$f" | cut -c1-16)" "$(stat -f '%z %Sm' -t '%Y-%m-%d' "$f")" "$f"; done
  echo '```'
} > "$OUT"
echo "wrote $OUT"
```

`chmod +x scripts/baseline-manifest.sh`, then run `scripts/baseline-manifest.sh 2026-09-06` and read the output file to make sure every section has content (the fork and Maven sections must not be empty on this Mac).

**Step 4: Run the remaining gates and record the outcomes**

Run each with a long timeout (up to 20 minutes) and note the last three lines:

- `scripts/verify.sh android`
- `scripts/verify.sh ios`
- `scripts/verify.sh embedded`

Append a section to `docs/baseline/2026-09-06.md`:

```markdown
## Gate results 2026-09-06

| Gate | Result | Notes |
|---|---|---|
| quick | PASS | |
| android | PASS/FAIL | first error line if any |
| ios | PASS/FAIL | |
| embedded | PASS/FAIL | |
| desktop | PASS/FAIL | |
```

A failing android/ios/embedded gate is a finding to record, not something to fix in this task. Quote the first `e:`/`FAILURE` line.

**Step 5: Commit**

```bash
git add scripts/verify.sh scripts/baseline-manifest.sh docs/baseline/2026-09-06.md
git commit -m "Add verification gates and a build baseline manifest

scripts/verify.sh runs the per-platform build gates used before and after
toolchain changes. scripts/baseline-manifest.sh snapshots submodule and
fork SHAs, the local Maven fork artifacts and prebuilt native libraries
the embedded profile depends on, so upgrades can be diffed and rolled
back. docs/baseline/2026-09-06.md is the pre-upgrade snapshot.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: Bring the docs back to reality (plan item T6a)

**Files:**
- Rewrite: `CLAUDE.md`
- Track: `docs/architecture-summary.md` (currently untracked; `git add` it)
- Modify: `README.md:89-92`, `README.md:106`
- Modify: `docs/FORKED_LIBRARIES.md:55-56`, `:139`, `:160`, `:253`, `:260`

**Step 1: Verify the fork paths before editing**

Run: `ls -d ../forks/koin/projects ../forks/jake/skiko ../forks/skiko 2>&1`
Expected: the first two exist, `../forks/skiko` does not.

**Step 2: Fix `docs/FORKED_LIBRARIES.md`**

- Lines 139 and 253: `cd forks/koin` → `cd forks/koin/projects` (that directory holds the Gradle root).
- Lines 160 and 260: `cd forks/skiko/skiko` → `cd forks/jake/skiko` (verify with `ls ../forks/jake/skiko/build.gradle.kts` or whatever the build file is and adjust if the Gradle root is one level deeper).
- Lines 55–56 in the ASCII diagram: `forks/koin` → `forks/koin/projects`, `forks/skiko` → `forks/jake/skiko`.
- Line 270 already carries `-Pembedded.enabled=true` from Task 1.
- Section "Build Configuration" (around lines 20–35, subsections "1. Repository ordering" and "2. Version forcing"): since Task 1 the mechanism is gated. Add one sentence at the top of the section: `All three layers below are active only when the embedded profile is on (embedded.enabled defaults to false in gradle.properties; pass -Pembedded.enabled=true or set it in ~/.gradle/gradle.properties).` Then refresh the stale line references: the `mavenLocal()` guard is at `settings.gradle.kts:42-45` (plus `:22-24` in pluginManagement) and the version-forcing block is `build.gradle.kts:30-65`. Verify both with `grep -n mavenLocal settings.gradle.kts` and `grep -n "resolutionStrategy\|embeddedEnabled" build.gradle.kts` before writing the numbers.
- Also note in the Known gaps of `CLAUDE.md` (Step 4 item 8): `data/remote/tor/native/build-linux-arm64.sh:220,223` still echoes embedded Gradle hints without the flag; it is under `native/` and is left for the Arti-upgrade task.

**Step 3: Fix `README.md`**

- Line 106: `| Desktop (JVM) | No | No | No | Uses Maven deps. Works out of the box. |` → `| Desktop (JVM) | No | No | No | Compiles from Maven deps. BLE and native location are macOS-only opt-ins (-PbleNative=macos, -PlocationNative=macos); Tor needs the Arti native library from build-all-desktop.sh. Linux desktop is untested. |`
- Lines 89–92: keep the commands but add one sentence above them: `Plain ./gradlew :apps:desktop:run works without any native build (no BLE, IP-based location).`

**Step 4: Rewrite `CLAUDE.md`**

Replace the whole file. Sources of truth: `settings.gradle.kts` (module list), `gradle/libs.versions.toml` and `gradle/wrapper/gradle-wrapper.properties` (versions), `docs/architecture-summary.md` (layering), `README.md` (prerequisites), `docs/FORKED_LIBRARIES.md` and `apps/embedded/EMBEDDED_NOTES.md` (embedded). Required content, in this order, under 250 lines:

1. One-paragraph overview: KMP rewrite of bitchat; targets Android, iOS, JVM desktop, opt-in Kotlin/Native linuxArm64 embedded (Orange Pi Zero 3). Protocol compatibility target and current drift: link `../docs/plans/2026-09-06-reentry-plan.md` (relative to the repo parent) for the state of interop with upstream.
2. Toolchain table with the REAL values: Kotlin, KSP, Gradle wrapper, AGP, Compose Multiplatform (from `settings.gradle.kts` pluginManagement), Compose BOM, Koin, Ktor, coroutines, JDK used (21 on this machine; 17+ required).
3. Module map: copy the exact `include(...)` list from `settings.gradle.kts` grouped as in `docs/architecture-summary.md` (apps, domain, data core, remote rest, remote transport incl. lora submodules, tor, presentation, iosdi). State explicitly: no `presentation:web`/WASM target, no Room database, no Android product flavors, no `data:repomock`.
4. Build / run / test commands that exist today: `scripts/verify.sh [quick|desktop|android|ios|embedded|full]`; `./gradlew :domain:jvmTest`; `./gradlew :apps:desktop:run` (+ the macOS native opt-ins); `./gradlew :apps:droid:assembleDebug` / `installDebug`; iOS: `./gradlew :iosdi:linkDebugFrameworkIosSimulatorArm64` then Xcode `apps/iosApp`; embedded: `./gradlew -Pembedded.enabled=true :apps:embedded:linkDebugExecutableLinuxArm64` with a pointer to `apps/embedded/README.md` for native prerequisites and deploy. Mention `--console=plain` and "never `clean` the native modules".
5. Native prerequisites: submodules (`git submodule update --init --recursive`), `brew install libsodium secp256k1`, Rust toolchain for Arti, Docker for the linuxArm64 cross build; where prebuilt artifacts live and that they are gitignored.
6. Embedded profile: what `-Pembedded.enabled=true` changes (includes `:apps:embedded`, mavenLocal first, forced fork versions from `gradle.properties`), pointer to `docs/FORKED_LIBRARIES.md`, `docs/baseline/`.
7. Architecture and conventions: Clean Architecture layering and dependency direction (from the summary doc), Koin modules per layer, `expect`/`actual` with source sets `commonMain`, `androidMain`, `iosMain`, `desktopMain`/`jvmMain`, `linuxArm64Main`; use cases as small classes; event buses; tests with kotlin.test + MockK + Turbine, JVM tests under `jvmTest`.
8. Known gaps and follow-ups (short bullets, each one line): desktop BLE is macOS-only (JNA dylib) and there is no Linux JVM BLE path; Linux desktop packaging/runtime never verified (Tor needs a `libarti_desktop.so` from `data/remote/tor/native/build-desktop.sh`; secure storage needs libsecret); `packageDmg` on a Homebrew JDK needs `GRADLE_ARGS='-Pcompose.desktop.packaging.checkJdkVendor=false'` (prefer Corretto/Temurin 21); upstream protocol drift makes current Android/iOS clients reject KMP mesh traffic (link `../docs/plans/2026-09-06-reentry-plan.md` §2); `data/remote/tor/native/build-linux-arm64.sh:220,223` still echoes embedded Gradle hints without `-Pembedded.enabled=true`; `IOS_MIN=13.0` in `data/crypto/native/build-*-ios.sh` and `data/noise/native/build-ios.sh` vs the Xcode deployment target 15.0; the `linux-arm64` libsodium prebuilt slice was built from an older submodule state (77 headers vs 76 in the iOS slices, see `docs/baseline/`), rebuild with `build-libsodium-linux-arm64.sh` when convenient; both platform `BleModule`s wire a `setOnPacketReceivedCallback` that `BluetoothMeshService.kt:94` immediately overwrites (dead code); `scripts/verify.sh ios` links sim-arm64 and arm64 device frameworks but not iosX64; on desktop startup Nostr relays answer `ERROR: bad req: invalid subscription id length` (subscription-id generation bug, pre-existing); Wire generates protos into `data/remote/transport/lora/meshtastic/src/commonMain/kotlin` so a Wire upgrade is a large source diff; `data/remote/tor/native/build-desktop.sh:99` and `build-linux-arm64.sh:146` assume `.git` is a directory (initialized submodules use a `.git` file); docs under `docs/` are mirrored into the owner's Obsidian vault but the repo is the source of truth.

Do NOT carry over any statement from the old `CLAUDE.md` without checking it against the sources above.

**Step 5: Verify the commands in `CLAUDE.md` exist**

Run: `./gradlew tasks --all --console=plain 2>/dev/null | grep -cE "linkDebugFrameworkIosSimulatorArm64|:apps:droid:assembleDebug|:apps:desktop:run"`
Expected: `3` (or 2 if `run` is listed under a different group; then run `./gradlew :apps:desktop:tasks --all | grep -w run`).

Run: `scripts/verify.sh`
Expected: `verify.sh quick: OK`.

**Step 6: Commit**

CLAUDE.md stays untracked: it is gitignored on purpose (see commit 1526d8d).

```bash
git add docs/architecture-summary.md README.md docs/FORKED_LIBRARIES.md
git commit -m "Rewrite CLAUDE.md from the real module and toolchain layout

The previous CLAUDE.md described Kotlin 2.3 Beta, Gradle 8.10, Room,
a WASM target, Android product flavors and module paths that do not
exist. Regenerate it from settings.gradle.kts, the version catalog and
docs/architecture-summary.md (now tracked). Fix the README desktop claim
and the fork paths in FORKED_LIBRARIES.md.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: Fix the Android gate (JVM target mismatch in `apps/droid`)

Found by Task 3: `scripts/verify.sh android` fails with
`Inconsistent JVM-target compatibility detected for tasks 'compileDebugJavaWithJavac' (17) and 'compileDebugKotlin' (21).`
`apps/droid/build.gradle.kts:37-39` sets `compileOptions` to Java 17, but no module in the repo sets a Kotlin `jvmTarget` or `jvmToolchain`, so the Kotlin Android plugin defaults to the JDK running Gradle (21 on this Mac). The KMP library modules are unaffected (the `com.android.kotlin.multiplatform.library` plugin aligns targets itself; `:presentation:design:compileDebugKotlinAndroid` passed in Task 2).

**Files:**
- Modify: `apps/droid/build.gradle.kts` (add a `kotlin { compilerOptions { ... } }` block next to `compileOptions`)

**Step 1: Reproduce**

Run: `scripts/verify.sh android 2>&1 | grep -E "Inconsistent JVM-target|BUILD"`
Expected: the inconsistency line and `BUILD FAILED`.

**Step 2: Pin the Kotlin JVM target to 17 in the app module**

`apps/droid` applies `org.jetbrains.kotlin.android` (see its `plugins {}` block). Add, at the top level of the file after the `android { ... }` block:

```kotlin
kotlin {
    compilerOptions {
        // Keep Kotlin's JVM target in step with android.compileOptions (Java 17); without this the
        // Kotlin Android plugin defaults to the JDK running Gradle and AGP rejects the mismatch.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
```

If the file already has a top-level `kotlin { }` block, add `compilerOptions { jvmTarget.set(...) }` inside it instead of creating a second block.

**Step 3: Verify**

Run: `scripts/verify.sh android 2>&1 | tail -4`
Expected: `BUILD SUCCESSFUL`, `verify.sh android: OK`, and `ls apps/droid/build/outputs/apk/debug/*.apk` shows an APK. First run may take 5–10 minutes (Android resource processing, 4 ABIs for Arti).

Run: `scripts/verify.sh` (quick) → `verify.sh quick: OK` (nothing else regressed).

**Step 4: Record and commit**

Update the android row of the gate table in `docs/baseline/2026-09-06.md` to `PASS | fixed in Task 5 (Kotlin jvmTarget pinned to 17)`.

```bash
git add apps/droid/build.gradle.kts docs/baseline/2026-09-06.md
git commit -m "Pin Kotlin jvmTarget to 17 in the Android app module

android.compileOptions targets Java 17 but nothing set Kotlin's jvmTarget,
so it defaulted to the JDK running Gradle (21) and AGP rejected the
mismatch. Aligns the app module with its own Java settings; the KMP
library modules already align through the AGP multiplatform plugin.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: Fix the iOS gate (regenerate the libsodium iOS prebuilts)

Found by Task 3: `scripts/verify.sh ios` fails in `:data:crypto:cinteropLibsodiumIosSimulatorArm64` with
`data/crypto/native/libsodium/build/ios-sim-arm64/include/sodium.h:29:10: fatal error: 'sodium/crypto_hash_sha3.h' file not found`.
The gitignored prebuilt tree under `data/crypto/native/libsodium/build/ios-sim-arm64/` is internally inconsistent: its `sodium.h` includes `sodium/crypto_hash_sha3.h` (present in the submodule source at `data/crypto/native/libsodium`, commit 28b8bc53) but the sibling `sodium/` directory (75 headers) does not contain it. `ios-arm64` and `ios-x64` may have the same skew. The existing script `data/crypto/native/build-libsodium-ios.sh` rebuilds all three iOS slices (`ios-x64`, `ios-arm64`, `ios-sim-arm64`) from the submodule with autotools; this task re-runs it. It writes only under the gitignored `build/` directories; the submodule source is not modified.

**Files:**
- Regenerate (gitignored): `data/crypto/native/libsodium/build/{ios-x64,ios-arm64,ios-sim-arm64}/`
- Modify: `docs/baseline/2026-09-06.md` (gate table row + native artifact hashes for the three libsodium slices)

**Step 1: Reproduce and inspect**

Run: `grep -c sha3 data/crypto/native/libsodium/build/ios-sim-arm64/include/sodium.h; ls data/crypto/native/libsodium/build/ios-sim-arm64/include/sodium/ | grep -c sha3; ls data/crypto/native/libsodium/src/libsodium/include/sodium/ | grep sha3`
Expected: `1`, `0`, and the source header listed — confirms the skew.

Run: `head -40 data/crypto/native/build-libsodium-ios.sh` and note its prerequisites (autoconf/automake/libtool via Homebrew, Xcode). Check they exist: `which autoconf automake glibtoolize xcrun`.

**Step 2: Rebuild the iOS slices**

Run (10–20 minutes; Bash timeout 30 minutes): `cd data/crypto/native && ./build-libsodium-ios.sh 2>&1 | tail -20`
Expected: the script's own success lines for all three slices; then `ls data/crypto/native/libsodium/build/ios-sim-arm64/include/sodium/ | grep -c sha3` → `1`, and `ls -la data/crypto/native/libsodium/build/*/lib/libsodium.a` shows today's date for the three iOS slices.

If the script fails, quote the first error line, do not try alternative build methods, and report — the fallback is to edit the prebuilt `sodium.h` to drop the `crypto_hash_sha3.h` include (a hack; only do it if told to).

**Step 3: Verify the iOS gate**

Run: `scripts/verify.sh ios 2>&1 | tail -4` (Bash timeout 20 minutes)
Expected: `BUILD SUCCESSFUL`, `verify.sh ios: OK`. If it now fails somewhere else (another cinterop, secp256k1, noise-c, arti), quote the first error line; that is a new finding to record, not to fix here.

Run: `git status --porcelain` → the libsodium submodule must NOT show as modified (only build/ output changed, which is gitignored). If it does, run `git -C data/crypto/native/libsodium status` and report; do not commit submodule changes.

**Step 4: Refresh the baseline and commit**

Run: `scripts/baseline-manifest.sh 2026-09-06`, then re-append the gate-results section from `git show HEAD:docs/baseline/2026-09-06.md | sed -n '/^## Gate results/,$p'` and update the ios row to `PASS | fixed in Task 6 (libsodium iOS prebuilts regenerated from submodule 28b8bc53)` (or FAIL with the new first error line).

```bash
git add docs/baseline/2026-09-06.md
git commit -m "Regenerate libsodium iOS prebuilts; iOS gate green

The gitignored ios-sim-arm64 libsodium headers were skewed (sodium.h
included crypto_hash_sha3.h, which the installed sodium/ directory
lacked), so the iOS cinterop failed. Rebuilt all three iOS slices from
the submodule with build-libsodium-ios.sh and refreshed the baseline
hashes and gate table.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: Fix the iOS gate (BLE Koin module lambda)

Found by Task 6: with libsodium regenerated, `scripts/verify.sh ios` now fails in `:data:remote:transport:bluetooth:compileKotlinIosSimulatorArm64`:
`data/remote/transport/bluetooth/src/iosMain/kotlin/com/bitchat/bluetooth/di/BleModule.kt:48:41 Argument type mismatch: ... 'OnPacketReceivedCallback' was expected.`
`OnPacketReceivedCallback` is a plain `interface` in `data/remote/transport/bluetooth/src/commonMain/kotlin/com/bitchat/bluetooth/service/BluetoothConnectionService.kt:22` (not a `fun interface`), so a lambda cannot be passed. The Android module (`src/androidMain/kotlin/com/bitchat/bluetooth/di/BleModule.kt:49`) already uses an `object : OnPacketReceivedCallback { ... }` expression; mirror it on iOS. Do not change the common interface.

**Files:**
- Modify: `data/remote/transport/bluetooth/src/iosMain/kotlin/com/bitchat/bluetooth/di/BleModule.kt:48-51`

**Step 1: Read the interface and the Android implementation**

`sed -n '20,30p' data/remote/transport/bluetooth/src/commonMain/kotlin/com/bitchat/bluetooth/service/BluetoothConnectionService.kt` and `sed -n '45,60p' data/remote/transport/bluetooth/src/androidMain/kotlin/com/bitchat/bluetooth/di/BleModule.kt` — note the exact method name and parameter types the interface declares.

**Step 2: Replace the lambda with an object expression**

Change

```kotlin
            setOnPacketReceivedCallback { data, deviceAddress ->
                val meshService: BluetoothMeshService = get()
                meshService.onPacketReceived(data, deviceAddress)
            }
```

to the same shape Android uses, e.g.

```kotlin
            setOnPacketReceivedCallback(object : OnPacketReceivedCallback {
                override fun onPacketReceived(data: ByteArray, deviceAddress: String) {
                    val meshService: BluetoothMeshService = get()
                    meshService.onPacketReceived(data, deviceAddress)
                }
            })
```

using the interface's real method signature, and add the `com.bitchat.bluetooth.service.OnPacketReceivedCallback` import if missing.

**Step 3: Verify**

Run: `scripts/verify.sh ios 2>&1 | tail -4` (20 minutes)
Expected: `BUILD SUCCESSFUL`, `verify.sh ios: OK`. If it fails further along, quote the first `e:`/`fatal error`/`What went wrong` lines; record, don't fix.

Run: `scripts/verify.sh` (quick) → OK. Run: `./gradlew :data:remote:transport:bluetooth:compileDebugKotlinAndroid --console=plain 2>&1 | tail -2` → BUILD SUCCESSFUL (nothing shared changed, sanity only).

**Step 4: Record and commit**

Update the ios row in `docs/baseline/2026-09-06.md` to `PASS | fixed in Task 6 (libsodium prebuilts) + Task 7 (iOS BleModule object expression)` (or FAIL with the new first error).

```bash
git add data/remote/transport/bluetooth/src/iosMain/kotlin/com/bitchat/bluetooth/di/BleModule.kt docs/baseline/2026-09-06.md
git commit -m "Fix iOS BLE module: OnPacketReceivedCallback is not a fun interface

The iOS Koin module passed a lambda to setOnPacketReceivedCallback, but
OnPacketReceivedCallback is a plain interface, so compileKotlinIosSimulatorArm64
failed. Use the same object expression the Android module uses.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 8: Pin the JVM bytecode target repo-wide

Task 5's review established that every Android/JVM library module compiles Kotlin at the level of the JDK running Gradle (class-file major 65 on this Mac) because none has Java sources to trigger the Kotlin Gradle plugin's JVM-target validation; `apps/droid` and `apps/desktop` tripped because they are single-platform `kotlin-android`/`kotlin-jvm` modules, where the check always runs (`KotlinCompile.validateKotlinAndJavaHasSameTargetCompatibility`); only KMP modules without Java sources skip it. `data/remote/tor/build.gradle.kts:184-187` still declares Java 1.8 `compileOptions`. Pin once at the root so bytecode level no longer depends on the developer's JDK, and drop the now-redundant app-level block. (Task 5's commit message says the library modules "already align through the AGP multiplatform plugin"; that was wrong — this task is the correction.)

**Files:**
- Modify: `build.gradle.kts` (root; next to the existing `tasks.withType<KotlinCompilationTask<*>>` block around lines 70–75)
- Modify: `apps/droid/build.gradle.kts` (remove the `kotlin { compilerOptions { jvmTarget ... } }` block added in Task 5)
- Modify: `data/remote/tor/build.gradle.kts:184-187` (`JavaVersion.VERSION_1_8` → `JavaVersion.VERSION_17`, both source and target)

**Step 1: Add the repo-wide pin**

Inside the root `subprojects { ... }` block, after the existing `KotlinCompilationTask` opt-in block, add:

```kotlin
    // Pin JVM/Android bytecode to Java 17 regardless of the JDK running Gradle. The KMP/AGP library
    // modules have no Java sources, so the Kotlin Gradle plugin's JVM-target validation never fired
    // for them and they silently tracked the host JDK (class-file 65 on a JDK 21 host).
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    // Plain kotlin("jvm") modules (apps/desktop) do run the validation, and their Java plugin
    // defaults compileJava to the host JDK, so pin Java to 17 there as well (AGP modules already
    // pin it through android.compileOptions).
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension>("java") {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
```

(First attempt without the `plugins.withId` block failed the quick gate with `Inconsistent JVM-target compatibility detected for tasks 'compileJava' (21) and 'compileKotlin' (17)` in `:apps:desktop:compileKotlin`.)

Then delete the `kotlin { compilerOptions { ... } }` block from `apps/droid/build.gradle.kts`, and change the two `JavaVersion.VERSION_1_8` in `data/remote/tor/build.gradle.kts` to `JavaVersion.VERSION_17`.

**Step 2: Verify**

- `scripts/verify.sh` (quick) → OK
- `scripts/verify.sh android 2>&1 | tail -3` → OK (15 minutes)
- `GRADLE_ARGS='-Pcompose.desktop.packaging.checkJdkVendor=false' scripts/verify.sh desktop 2>&1 | tail -3` → OK (packaging re-runs because bytecode changed; 10 minutes)
- Bytecode check: `javap -verbose -cp apps/droid/build/tmp/kotlin-classes/debug com.bitchat.android.MainActivity 2>/dev/null | grep -m1 "major version"` → `61`; and for a library, e.g. `find data/repo/build -name '*.class' -path '*debug*' | head -1 | xargs javap -verbose | grep -m1 "major version"` → `61` (adjust the path to whatever class dir exists).
- Embedded gate is unaffected (Kotlin/Native), skip.

**Step 3: Commit**

```bash
git add build.gradle.kts apps/droid/build.gradle.kts data/remote/tor/build.gradle.kts
git commit -m "Pin Kotlin JVM target to 17 for every JVM and Android compilation

Library modules compiled Kotlin at whatever JDK ran Gradle because they
have no Java sources to trigger the JVM-target validation; only the app
module (BuildConfig.java) tripped it. Pin once at the root so bytecode is
reproducible across hosts, raise data:remote:tor's Java 1.8 compileOptions
to 17, and drop the app-level pin from Task 5, which this supersedes.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 9: Gate and manifest hygiene (from the Tasks 6/7 review)

Three follow-ups from review: the `ios` gate links only the simulator slice, so the regenerated `ios-arm64` device slice is unverified; `scripts/baseline-manifest.sh` overwrites the whole file, so the hand-appended gate table is lost on every regeneration; and the manifest hashes archives but not headers, which is the exact skew class Task 6 fixed.

**Files:**
- Modify: `scripts/verify.sh` (`ios` case + header comment)
- Modify: `scripts/baseline-manifest.sh`
- Regenerate: `docs/baseline/2026-09-06.md`

**Step 1: Link the device framework too**

In `scripts/verify.sh` change the `ios` case to
```bash
  ios)      gradle :iosdi:linkDebugFrameworkIosSimulatorArm64 :iosdi:linkDebugFrameworkIosArm64 ;;
```
and update the header comment line for `ios` to `# :iosdi debug frameworks for iosSimulatorArm64 and iosArm64 (iosX64 slice is not exercised)`.

**Step 2: Preserve the gate table and hash header sets**

In `scripts/baseline-manifest.sh`:
- Before the `{ ... } > "$OUT"` block, capture any existing gate section:
  ```bash
  GATES="$( [ -f "$OUT" ] && sed -n '/^## Gate results/,$p' "$OUT" || true )"
  ```
  and after the block, re-append it:
  ```bash
  if [ -n "$GATES" ]; then printf '\n%s\n' "$GATES" >> "$OUT"; fi
  ```
- Add a section after the native archives (hash the *contents* of the include dirs the cinterop settings point at, not a glob over install copies and cargo output):
  ```bash
  echo
  echo "## Native header sets (hash of header contents per include dir the build reads)"
  echo '```'
  # macOS-only: shasum. Dirs mirror the includeDirs()/cinterop settings in data/*/build.gradle.kts.
  for inc in \
    data/crypto/native/libsodium/build/*/include \
    data/crypto/native/secp256k1/build/{ios-arm64,ios-simulator-fat,linux-arm64}/include \
    data/noise/native/noise-c/include \
    data/remote/transport/bluetooth/native/gattlib/include \
    data/remote/transport/bluetooth/native/include \
    data/remote/tor/native/arti-ios-wrapper \
    data/remote/tor/native/arti-linux-wrapper; do
    [ -d "$inc" ] || continue
    n=$(find "$inc" -name '*.h' -type f -not -path '*/target/*' | wc -l | tr -d ' ')
    [ "$n" -gt 0 ] || continue
    h=$(cd "$inc" && find . -name '*.h' -type f -not -path '*/target/*' | sort | xargs shasum -a 256 | shasum -a 256 | cut -c1-16)
    printf '%s  %4s headers  %s\n' "$h" "$n" "$inc"
  done
  echo '```'
  ```
- The script also sets `export LC_ALL=C` right after `set -euo pipefail` so sort/hash order does not depend on the caller's locale, and writes to `"$OUT.tmp"` then `mv`s it into place so a failure mid-run cannot leave a truncated document.

**Step 3: Verify**

- `bash -n scripts/verify.sh scripts/baseline-manifest.sh`
- `scripts/baseline-manifest.sh 2026-09-06` → the regenerated file still ends with the `## Gate results 2026-09-06` table (check with `tail -12`), and has a non-empty "Native header sets" section listing the libsodium/secp256k1/noise-c include dirs.
- `scripts/verify.sh ios 2>&1 | tail -3` (20 minutes) → `verify.sh ios: OK` (now includes the device link). If the device link fails, quote the first error line and record it in the ios row instead.
- Update the ios row note to `... ; gate links sim-arm64 and arm64 device frameworks`.

**Step 4: Commit**

```bash
git add scripts/verify.sh scripts/baseline-manifest.sh docs/baseline/2026-09-06.md
git commit -m "Gate hygiene: link the iOS device framework, keep gate results, hash headers

The ios gate now links both the simulator and device frameworks so all
regenerated prebuilt slices are exercised. The baseline manifest preserves
the hand-maintained gate-results section across regenerations and records
a hash of each native include directory's header list, so header/archive
skew like the libsodium one fixed in Task 6 shows up in a diff.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

## Review checkpoints

- After each task: `superpowers:code-reviewer` subagent with the task's base and head SHAs.
- After Task 2 and after Task 4: Codex (`gpt-6-astra`, read-only) reviews `git diff main..reentry/session-1`.
- Session ends with `superpowers:finishing-a-development-branch`.

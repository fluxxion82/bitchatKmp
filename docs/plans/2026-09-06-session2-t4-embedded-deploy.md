# Session 2 / T4: Embedded Build Identity and Pi Deploy

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (fresh subagent per task, superpowers:code-reviewer after each task).

**Goal:** Every embedded binary knows which commit it was built from and says so at startup; one script builds, ships, verifies and restarts it on the Orange Pi as a systemd service.

**Architecture:** A Gradle task in `apps/embedded/build.gradle.kts` generates `EmbeddedBuildInfo.kt` (git SHA, branch, dirty flag, build time, version) from `providers.exec` (configuration-cache safe). A hand-written `BuildIdentity.kt` formats the one-line identity and is printed at startup, returned by `bitchat-embedded.kexe --version`, and injected into `AppInformation` through the existing `buildConfigModule`. `scripts/deploy-pi.sh` links the binary, stages it with `compose-resources/`, a `SHA256SUMS` manifest, a `BUILD_INFO` file and the unit file, rsyncs it to `/opt/bitchat/releases/<sha>-<build>/`, verifies it on the Pi (`sha256sum -c` and `--version`), swaps the `/opt/bitchat/releases/current` symlink atomically, installs `bitchat.service` through the pre-approved sudoers rule and restarts it.

**Tech Stack:** Gradle 9.2.1 (configuration cache ON), Kotlin/Native 2.2.10 linuxArm64, bash, rsync (openrsync on macOS, rsync 3.4.1 on the Pi), systemd on Armbian (Debian forky).

**Context you need:**
- Repo: `/Users/fluxxion/Development/workspace/multiplatform/bitchat/bitchatKmp`. Branch: `reentry/session-2` (work in the main checkout, not a worktree).
- Background: `../docs/plans/2026-09-06-reentry-plan.md` (§4 row T4, §5b "Session 2 kickoff") and `../docs/reviews/2026-09-06-codex-plan-critique.md` §4 (Codex's starting Gradle snippet; this plan refines it).
- Embedded builds need `-Pembedded.enabled=true` on every Gradle call and this Mac's `~/.m2` fork artifacts (present). Always pass `--console=plain`. Never run `./gradlew clean`. Builds are slow: a relink of `:apps:embedded` takes minutes; use timeouts of 10 minutes.
- `scripts/verify.sh embedded` is the gate (`linkDebugExecutableLinuxArm64`). Keep it green and keep it fast: on a clean tree a second run must be `UP-TO-DATE`.
- Do not touch `../forks`, `~/.m2`, submodules, or anything under `native/`.
- Commit messages: short imperative summary, body explaining why, and end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Pi facts (verified 2026-09-06): `ssh sterling@192.168.4.58` with key auth (no password prompt; `-o BatchMode=yes` works). Pass commands as the ssh argument, not via stdin. First connection after idle can take 20 s: use `-o ConnectTimeout=60`. `/opt/bitchat` is `root:root 755`; `/opt/bitchat/releases` is `sterling:sterling 755`. Sudoers (`sudo -n` works) allows exactly: `systemctl daemon-reload`, `systemctl {start,stop,restart,status,enable,disable,is-active} bitchat.service`, and `install -m 644 -o root -g root /opt/bitchat/bitchat.service /etc/systemd/system/bitchat.service`. sterling is in groups `video`, `render`, `input`, `systemd-journal`. `/dev/dri/card0` is `root:video 660`. Old binaries `~/bitchat-embedded.kexe` and `/opt/bitchat/bitchat-embedded-new.kexe` are not running and are not to be deleted. Runtime libs (libdrm, libgbm, libEGL, libGLESv2, fontconfig, freetype) are installed. `rsync`, `sha256sum`, `journalctl` exist on the Pi.
- The Compose resource reader in the fork resolves `compose-resources/` next to the executable via `/proc/self/exe` (see `apps/embedded/EMBEDDED_NOTES.md`), so the directory must sit beside the binary in each release dir.

---

### Task 1: Generate `EmbeddedBuildInfo.kt`, print identity at startup, add `--version`

**Files:**
- Modify: `apps/embedded/build.gradle.kts` (append after the `kotlin { ... }` block)
- Create: `apps/embedded/src/linuxArm64Main/kotlin/com/bitchat/embedded/BuildIdentity.kt`
- Modify: `apps/embedded/src/linuxArm64Main/kotlin/com/bitchat/embedded/Main.kt:139-146` (entry point) and the banner at the top of `main`
- Modify: `apps/embedded/src/linuxArm64Main/kotlin/com/bitchat/embedded/di/BuildConfigModule.kt`
- Modify: `.gitignore` is already fine (`build/` is ignored); do not add anything.

**Step 1: Add the generator to `apps/embedded/build.gradle.kts`**

Append this after the closing brace of the `kotlin { ... }` block:

```kotlin
// ---------------------------------------------------------------------------
// Build identity. A Gradle task writes EmbeddedBuildInfo.kt so the binary can
// report the commit it was built from (printed at startup and by --version).
// Uses providers.exec so it stays configuration-cache safe. On a clean tree the
// generated file is a pure function of (version, sha, branch) and the commit
// time, so the task, compile and link stay UP-TO-DATE. On a dirty tree the
// build time is the wall clock and the task reruns every build on purpose.
// ---------------------------------------------------------------------------
version = providers.gradleProperty("embedded.version").orElse("1.0.0").get()

fun git(vararg args: String): Provider<String> = providers.exec {
    workingDir(rootProject.projectDir)
    commandLine("git", *args)
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim() }

val gitSha = git("rev-parse", "--verify", "HEAD").map { it.ifEmpty { "unknown" } }
val gitBranch = git("rev-parse", "--abbrev-ref", "HEAD").map { it.ifEmpty { "unknown" } }
val gitCommitTime = git("show", "-s", "--format=%cI", "HEAD").map { it.ifEmpty { "unknown" } }
val gitDirty = git("status", "--porcelain", "--untracked-files=no", "--ignore-submodules=dirty")
    .map { it.isNotEmpty() }

val generateEmbeddedBuildInfo = tasks.register("generateEmbeddedBuildInfo") {
    group = "build"
    description = "Writes EmbeddedBuildInfo.kt (git SHA, branch, dirty flag, build time, version)."
    val outputDir = layout.buildDirectory.dir("generated/embeddedBuildInfo/kotlin")
    inputs.property("version", version.toString())
    inputs.property("gitSha", gitSha)
    inputs.property("gitBranch", gitBranch)
    inputs.property("gitCommitTime", gitCommitTime)
    inputs.property("gitDirty", gitDirty)
    outputs.dir(outputDir)
    outputs.upToDateWhen { !gitDirty.get() }
    outputs.cacheIf { !gitDirty.get() }
    doLast {
        val props = inputs.properties
        val dirty = props.getValue("gitDirty") as Boolean
        val builtAt = if (dirty) java.time.Instant.now().toString() else props.getValue("gitCommitTime").toString()
        val dir = outputDir.get().asFile.resolve("com/bitchat/embedded")
        dir.mkdirs()
        dir.resolve("EmbeddedBuildInfo.kt").writeText(
            """
            |// Generated by :apps:embedded:generateEmbeddedBuildInfo. Do not edit.
            |package com.bitchat.embedded
            |
            |internal object EmbeddedBuildInfo {
            |    const val VERSION = "${props.getValue("version")}"
            |    const val GIT_SHA = "${props.getValue("gitSha")}"
            |    const val GIT_BRANCH = "${props.getValue("gitBranch")}"
            |    const val GIT_DIRTY = $dirty
            |    const val BUILT_AT = "$builtAt"
            |}
            |""".trimMargin()
        )
    }
}

kotlin.sourceSets.named("linuxArm64Main") {
    kotlin.srcDir(generateEmbeddedBuildInfo)
}
```

Notes for the implementer:
- `kotlin.srcDir(taskProvider)` wires the task dependency automatically; do not add `dependsOn` by hand.
- If `Provider` does not resolve, import `org.gradle.api.provider.Provider` at the top of the file.
- Do not use `outputs.upToDateWhen { false }` unconditionally (Codex's draft did); that would relink on every `verify.sh embedded` run.

**Step 2: Verify the generator alone**

Run: `./gradlew -Pembedded.enabled=true :apps:embedded:generateEmbeddedBuildInfo --console=plain`
Expected: `BUILD SUCCESSFUL`, and `apps/embedded/build/generated/embeddedBuildInfo/kotlin/com/bitchat/embedded/EmbeddedBuildInfo.kt` exists with the current `git rev-parse HEAD`, `GIT_BRANCH = "reentry/session-2"`, `GIT_DIRTY = true` (the tree is dirty while you edit) and an ISO-8601 `BUILT_AT`.

Run it a second time. Expected: task executes again (dirty tree). That is by design.

**Step 3: Add `BuildIdentity.kt`**

```kotlin
package com.bitchat.embedded

import kotlin.experimental.ExperimentalNativeApi

/**
 * Human-readable build identity assembled from the generated [EmbeddedBuildInfo].
 * Printed at startup, returned by `bitchat-embedded.kexe --version`, and exposed to the app
 * through `AppInformation` (see `di/BuildConfigModule.kt`).
 */
@OptIn(ExperimentalNativeApi::class)
internal object BuildIdentity {
    const val NAME = "bitchat-embedded"

    val shortSha: String = EmbeddedBuildInfo.GIT_SHA.take(12)

    val isDebug: Boolean = Platform.isDebugBinary

    /** Example: `bitchat-embedded 1.0.0 (65d65087cd41, reentry/session-2, clean, debug, built 2026-09-06T20:25:33-07:00)` */
    val line: String = buildString {
        append(NAME).append(' ').append(EmbeddedBuildInfo.VERSION)
        append(" (").append(shortSha)
        append(", ").append(EmbeddedBuildInfo.GIT_BRANCH)
        append(", ").append(if (EmbeddedBuildInfo.GIT_DIRTY) "dirty" else "clean")
        append(", ").append(if (isDebug) "debug" else "release")
        append(", built ").append(EmbeddedBuildInfo.BUILT_AT)
        append(')')
    }
}
```

**Step 4: Wire `Main.kt`**

Change the entry point so it accepts arguments, answers `--version` without touching DRM, and prints the identity line right after the banner:

```kotlin
@OptIn(ExperimentalFoundationApi::class, InternalCoroutinesApi::class)
fun main(args: Array<String>) {
    if (args.any { it == "--version" || it == "-v" }) {
        println(BuildIdentity.line)
        return
    }
    runApp()
}

@OptIn(ExperimentalFoundationApi::class, InternalCoroutinesApi::class)
private fun runApp() = memScoped {
    val mainDispatcher = FlushCoroutineDispatcher()
    ComposeUiMainDispatcher = mainDispatcher

    println("=== Bitchat Embedded ===")
    println(BuildIdentity.line)
    println("Initializing application...")
    // ... rest of the existing body of main() unchanged ...
}
```

Keep the existing `entryPoint = "com.bitchat.embedded.main"` in `build.gradle.kts`; Kotlin/Native accepts `main(args: Array<String>)` there.

**Step 5: Wire `BuildConfigModule.kt`**

Replace the `AppInformation` single and delete the now-unused `toVersion()` helper:

```kotlin
package com.bitchat.embedded.di

import com.bitchat.domain.initialization.AppInitializer
import com.bitchat.domain.initialization.models.AppInformation
import com.bitchat.domain.initialization.models.Version
import com.bitchat.embedded.BuildIdentity
import com.bitchat.embedded.EmbeddedBuildInfo
import org.koin.dsl.bind
import org.koin.dsl.module

val buildConfigModule = module {
    single {
        AppInformation(
            version = Version(
                name = EmbeddedBuildInfo.VERSION,
                build = BuildIdentity.shortSha,
                additionalInfo = BuildIdentity.line,
            ),
            versionCode = 1,
            id = "com.bitchat.embedded",
            debug = BuildIdentity.isDebug,
        )
    }

    // Auto-activate user state for embedded (no onboarding UI)
    single {
        EmbeddedUserStateInitializer(
            userRepository = get(),
            userEventBus = get(),
        )
    } bind AppInitializer::class
}
```

`debug` only feeds `getEngine(isDebug, ...)` in `data/remote/rest/client`, whose Linux actual ignores it, so this is not a behaviour change.

**Step 6: Link and check up-to-date behaviour**

Run: `scripts/verify.sh embedded`
Expected: `verify.sh embedded: OK` (this compiles and links; several minutes).

Commit everything (Step 7) FIRST so the tree is clean, then run `scripts/verify.sh embedded` twice more:
- First run after the commit: `generateEmbeddedBuildInfo` executes (sha changed, dirty flipped to false), compile and link execute.
- Second run: `:apps:embedded:generateEmbeddedBuildInfo UP-TO-DATE`, `:apps:embedded:compileKotlinLinuxArm64 UP-TO-DATE`, `:apps:embedded:linkDebugExecutableLinuxArm64 UP-TO-DATE`.
If the second run is not up-to-date, the inputs are not stable; fix before reporting.

Then inspect the generated file: `GIT_DIRTY = false` and `BUILT_AT` equals `git show -s --format=%cI HEAD`.

**Step 7: Commit**

```bash
git add apps/embedded/build.gradle.kts apps/embedded/src/linuxArm64Main/kotlin/com/bitchat/embedded/BuildIdentity.kt apps/embedded/src/linuxArm64Main/kotlin/com/bitchat/embedded/Main.kt apps/embedded/src/linuxArm64Main/kotlin/com/bitchat/embedded/di/BuildConfigModule.kt
git commit -m "Stamp the embedded binary with its git identity

The binaries on the Orange Pi carried no version information, so nothing
on the device could be traced back to a commit. A Gradle task now generates
EmbeddedBuildInfo.kt (SHA, branch, dirty flag, build time, version) through
providers.exec, which keeps the configuration cache valid. The identity line
is printed at startup, returned by \`bitchat-embedded.kexe --version\` (no DRM
needed, so a deploy script can check it), and exposed through AppInformation.
Clean trees use the commit time as build time so the embedded gate stays
UP-TO-DATE; dirty trees are stamped with the wall clock on every build.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

**Report:** the generated file content, the three `verify.sh embedded` results (with the UP-TO-DATE lines from the last run), and the commit SHA.

---

### Task 2: `bitchat.service` unit and `scripts/deploy-pi.sh`

**Files:**
- Create: `apps/embedded/systemd/bitchat.service`
- Create: `scripts/deploy-pi.sh` (executable)

No Pi access is needed for this task; Task 3 runs it for real. Design the script so that everything up to the upload can be exercised locally with `--dry-run`.

**Step 1: The unit file**

```ini
# Installed by scripts/deploy-pi.sh as /etc/systemd/system/bitchat.service.
# The binary and its compose-resources/ live in /opt/bitchat/releases/<release>/;
# /opt/bitchat/releases/current is a symlink the deploy script swaps atomically.
[Unit]
Description=Bitchat embedded UI (DRM/EGL, Orange Pi Zero 3)
After=multi-user.target xpt2046-touch.service cardkb.service

[Service]
Type=simple
User=sterling
Group=sterling
SupplementaryGroups=video render input
WorkingDirectory=/opt/bitchat/releases/current
ExecStart=/opt/bitchat/releases/current/bitchat-embedded.kexe
Environment=LANG=en_US.UTF-8
Restart=on-failure
RestartSec=5
TimeoutStopSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

[Annotation, added after the fact: the `After=` line in the snippet above is correct and cycle-free — keep all three names. systemd adds an implicit `After=` from a target to every unit it `Wants`, *unless* an ordering dependency between the target and that unit already exists; naming `multi-user.target` in `After=` is exactly what suppresses the implicit `multi-user.target` -> `bitchat.service` edge. A later change dropped `multi-user.target` from `After=` while keeping `cardkb.service` and `xpt2046-touch.service`, which left the implicit edge in place and closed the cycle bitchat -> cardkb -> multi-user.target -> bitchat; at the next boot systemd printed `Found ordering cycle ... Job bitchat.service/start deleted` and the app did not come up. Restoring the target in `After=` is the fix. The unit later also gained `ExecStartPre=/opt/bitchat/releases/current/wait-for-input-devices.sh` and `TimeoutStartSec=120`.]

Why no hardening directives: the app writes `~/.bitchat/`, opens `/dev/dri/card0`, `/dev/input/event*` and I2C/SPI devices; `ProtectHome`/`ProtectSystem=strict` would break it. Add hardening later once the device list is known.

**Step 2: The deploy script**

Write `scripts/deploy-pi.sh` with this contract (keep it under ~200 lines, `set -euo pipefail`, bash 3.2 compatible because macOS ships bash 3.2: no associative arrays, no `mapfile`, no `${var,,}`):

```
Usage: scripts/deploy-pi.sh [options]

Builds the linuxArm64 embedded binary, stages it with compose-resources/,
a SHA256SUMS manifest, BUILD_INFO and bitchat.service, uploads it to
$PI_HOST:/opt/bitchat/releases/<sha12>[-dirty-<utc-stamp>]-<build>/,
verifies it on the device, swaps /opt/bitchat/releases/current and
restarts bitchat.service.

Options:
  --release          link the release binary (default: debug)
  --debug            link the debug binary
  --no-build         reuse the existing link output
  --no-restart       upload, verify and switch, but do not install/restart the unit
  --dry-run          build and stage locally, print what would be uploaded, no ssh
  --host USER@HOST   target (default: $PI_HOST or sterling@192.168.4.58)
  -h, --help
```

Required behaviour, in order:

1. Resolve `REPO` from the script location (`cd "$(dirname "$0")/.."`). Parse options. `BUILD` is `debug` or `release`; the Gradle task is `link${Build}ExecutableLinuxArm64` with `Build` capitalised (`Debug`/`Release`); the output dir is `apps/embedded/build/bin/linuxArm64/${BUILD}Executable`.
2. Unless `--no-build`: `./gradlew -Pembedded.enabled=true ":apps:embedded:$TASK" --console=plain`.
3. Read identity from `apps/embedded/build/generated/embeddedBuildInfo/kotlin/com/bitchat/embedded/EmbeddedBuildInfo.kt` with `sed -n 's/.*const val GIT_SHA = "\(.*\)"/\1/p'` etc. for `GIT_SHA`, `GIT_DIRTY`, `BUILT_AT`, `VERSION`, `GIT_BRANCH`. Fail with a clear message if the file or the binary is missing.
4. Release name: `SHA12=${GIT_SHA:0:12}`; `NAME="$SHA12-$BUILD"`; if `GIT_DIRTY` is `true`, `NAME="$SHA12-dirty-$(date -u +%Y%m%dT%H%M%SZ)-$BUILD"`.
5. Stage into `STAGE=$(mktemp -d "${TMPDIR:-/tmp}/bitchat-deploy.XXXXXX")` (trap to remove on exit): copy `bitchat-embedded.kexe` (mode 755), `compose-resources/` (recursively; fail if absent), `apps/embedded/systemd/bitchat.service`, and write `BUILD_INFO` with `key=value` lines: `name`, `version`, `git_sha`, `git_branch`, `git_dirty`, `built_at`, `build`, `deployed_from=$(hostname)`, `deployed_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)`. Then, from inside `$STAGE`: `find . -type f ! -name SHA256SUMS -print0 | LC_ALL=C sort -z | xargs -0 shasum -a 256 > SHA256SUMS`. Verify locally right away with `shasum -a 256 -c SHA256SUMS --quiet` (fail on error).
6. `--dry-run`: print the release name, `BUILD_INFO`, `SHA256SUMS`, the total staged size (`du -sh`), and exit 0 without ssh.
7. Preflight: `ssh -o BatchMode=yes -o ConnectTimeout=60 "$HOST" 'test -w /opt/bitchat/releases'`; on failure explain that key auth and `/opt/bitchat/releases` (sterling-owned) are required.
8. Upload: `rsync -a --delete "$STAGE/" "$HOST:/opt/bitchat/releases/$NAME/"`. Use `ssh` options via `-e "ssh -o BatchMode=yes -o ConnectTimeout=60"`.
9. Verify on the Pi in one ssh call: `cd /opt/bitchat/releases/$NAME && sha256sum --quiet -c SHA256SUMS && ./bitchat-embedded.kexe --version`. Capture the output; require it to contain `$SHA12`; otherwise fail and leave the release dir in place for inspection.
10. Switch (atomic): `ln -sfn "/opt/bitchat/releases/$NAME" /opt/bitchat/releases/current.tmp && mv -T /opt/bitchat/releases/current.tmp /opt/bitchat/releases/current`.
11. Unless `--no-restart`: install the unit and restart, all in one ssh call:
    ```
    cat "/opt/bitchat/releases/$NAME/bitchat.service" > /opt/bitchat/bitchat.service \
      && sudo -n install -m 644 -o root -g root /opt/bitchat/bitchat.service /etc/systemd/system/bitchat.service \
      && sudo -n systemctl daemon-reload \
      && sudo -n systemctl enable bitchat.service \
      && sudo -n systemctl restart bitchat.service
    ```
    `/opt/bitchat/bitchat.service` must already exist and be owned by sterling (the directory is root-owned, so only in-place truncation works; Task 3 creates that placeholder once). If the `cat >` fails, print: `"/opt/bitchat/bitchat.service is missing or not writable by sterling; create it once with: sudo install -o sterling -g sterling -m 644 /dev/null /opt/bitchat/bitchat.service"`.
    Then `sleep 3` and, in one ssh call, `sudo -n systemctl is-active bitchat.service; journalctl -u bitchat.service -n 25 --no-pager -o cat`. Require the journal output to contain `$SHA12`; if `is-active` is not `active`, exit 1 after printing the journal.
12. Final summary lines: release path, `current ->` target, the identity line from `--version`, and the service state.

Every remote command is passed as the ssh argument (never via stdin), e.g. `ssh $SSH_OPTS "$HOST" "cd '/opt/bitchat/releases/$NAME' && sha256sum --quiet -c SHA256SUMS && ./bitchat-embedded.kexe --version"`. Quote `$NAME` and paths.

**Step 3: Local checks**

Run: `bash -n scripts/deploy-pi.sh` (no output).
Run: `scripts/deploy-pi.sh --no-build --dry-run` (Task 1 left a debug link output in place).
Expected: prints release name `<sha12>-debug` (or `-dirty-...` if the tree is dirty while you work), `BUILD_INFO`, `SHA256SUMS` listing `./bitchat-embedded.kexe`, `./bitchat.service`, `./BUILD_INFO` and the `./compose-resources/...` files, size around 130 MB, exit 0, and the temp dir is gone afterwards (`ls "${TMPDIR:-/tmp}" | grep bitchat-deploy` prints nothing).
Run: `scripts/deploy-pi.sh --help` prints usage and exits 0; an unknown option exits 2 with usage on stderr.

**Step 4: Commit**

```bash
chmod +x scripts/deploy-pi.sh
git add apps/embedded/systemd/bitchat.service scripts/deploy-pi.sh
git commit -m "Add deploy-pi.sh and a bitchat.service unit for the Orange Pi

Deploying the embedded binary was a hand-typed scp plus a manual run, so
the device ended up with untraceable binaries and no supervisor. The script
links the binary, stages it with compose-resources/, a SHA256SUMS manifest
and BUILD_INFO into a release directory named after the git SHA, uploads it
with rsync, verifies the checksums and the --version output on the device,
swaps the current symlink atomically and (re)starts bitchat.service through
the narrow sudoers rule already installed on the Pi.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

**Report:** the dry-run output and the commit SHA.

---

### Task 3: First real deploy, fix what breaks, document

Prerequisite (done by the session lead, not the subagent, because it needs the Pi password once): `/opt/bitchat/bitchat.service` exists on the Pi and is owned by sterling.

**Files:**
- Modify: `apps/embedded/README.md` ("Deploy and run" section, lines ~108-113)
- Modify: `README.md` ("Embedded Device Quickstart", steps 3-4, lines ~171-178)
- Modify: `apps/embedded/EMBEDDED_NOTES.md` (short note on `--version` and the release layout, next to the compose-resources section)
- Possibly modify: `scripts/deploy-pi.sh`, `apps/embedded/systemd/bitchat.service` (whatever the real run reveals)

**Step 1: Deploy**

Run from the repo root: `scripts/deploy-pi.sh` (debug build, may take several minutes; use a 15-minute timeout).
Expected: the script ends with the identity line containing the current HEAD's short SHA and `bitchat.service` `active`.

If it fails, read the printed journal, fix the script or unit, commit the fix with a message explaining what the device revealed, and rerun. Common suspects: the `mv -T` symlink swap, `SupplementaryGroups`, the `LANG` locale, DRM master held by another process (`sudo -n systemctl status bitchat.service` and `journalctl -u bitchat.service -n 50 --no-pager`).

**Step 2: Prove idempotence**

Run `scripts/deploy-pi.sh` a second time without changes. Expected: Gradle reports `UP-TO-DATE` for generate/compile/link, rsync transfers nothing significant, the same release dir is reused, service restarts and reports the same SHA.

**Step 3: Check the device state**

Run: `ssh -o BatchMode=yes -o ConnectTimeout=60 sterling@192.168.4.58 'ls -la /opt/bitchat/releases; readlink -f /opt/bitchat/releases/current; cat /opt/bitchat/releases/current/BUILD_INFO; sudo -n systemctl is-enabled bitchat.service; sudo -n systemctl is-active bitchat.service'`
Record the output in your report.

**Step 4: Docs**

- `apps/embedded/README.md` "Deploy and run": replace the scp/ssh pair with `scripts/deploy-pi.sh` (mention `--release`, `--dry-run`, `PI_HOST`), the release layout under `/opt/bitchat/releases/`, the one-time placeholder command for `/opt/bitchat/bitchat.service`, and how to read logs (`journalctl -u bitchat.service -f`).
- `README.md` quickstart: steps 3-4 become `scripts/deploy-pi.sh --release`.
- `apps/embedded/EMBEDDED_NOTES.md`: two short paragraphs: `bitchat-embedded.kexe --version` prints the identity without touching DRM; release dirs contain `compose-resources/` beside the binary and `current` is a symlink.

**Step 5: Commit**

```bash
git add apps/embedded/README.md README.md apps/embedded/EMBEDDED_NOTES.md
git commit -m "Document the deploy-pi.sh workflow

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```
(Use a body if the run changed anything else worth explaining.)

**Report:** first-run and second-run summaries, the device-state output, every fix you had to make, and commit SHAs.

---

## Review checkpoints

- After Task 1 and Task 2: superpowers:code-reviewer.
- After Task 2: Codex (gpt-6-astra, read-only) review of the diff `main..HEAD`.
- After Task 3: superpowers:code-reviewer, then a final Codex review of the branch and `scripts/verify.sh full` before finishing.

## Out of scope (record, do not do)

- Showing the identity in the Settings screen: no platform currently renders `AppInformation` anywhere in `presentation/*`; needs a small viewmodel/screen change and belongs with the T8/UI work.
- Pruning old release directories on the Pi (447 GB free); and deleting `~/bitchat-embedded.kexe` / `/opt/bitchat/bitchat-embedded-new.kexe` (neither can be traced to a commit; leave them for the owner).
- Reproducible-release mode with an explicit timestamp input.

import java.security.MessageDigest
import java.time.Instant

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.jetbrains.compose)
}

val composeVersion = providers.gradleProperty("embedded.composeForkVersion")
    .orElse("9999.0.0-SNAPSHOT")
    .get()
val skikoVersion = providers.gradleProperty("embedded.skikoVersion")
    .orElse("0.9.47")
    .get()
val koinVersion = providers.gradleProperty("embedded.koinForkVersion")
    .orElse("4.2.2")
    .get()

// Pin the linuxArm64 Skiko artifact and the forked Compose for linuxArm64.
// This handles transitive dependencies from presentation modules.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.skiko" && requested.name == "skiko") {
            // Kotlin/Native cannot resolve the multiplatform metadata module for this target,
            // so redirect to the published platform artifact.
            useTarget("org.jetbrains.skiko:skiko-linuxarm64:$skikoVersion")
            because("Kotlin/Native needs the explicit linuxarm64 Skiko artifact")
        }
        // Force forked Compose artifacts for linuxArm64 support
        // Exclude components group - it's published per-platform, not as multiplatform module
        val composeGroups = listOf(
            "org.jetbrains.compose.ui",
            "org.jetbrains.compose.foundation",
            "org.jetbrains.compose.material",
            "org.jetbrains.compose.material3",
            "org.jetbrains.compose.animation",
            "org.jetbrains.compose.runtime"
        )
        if (requested.group in composeGroups) {
            useVersion(composeVersion)
            because("Using forked Compose with linuxArm64 support")
        }
        // For components-resources, force all artifacts to SNAPSHOT (has linuxArm64)
        if (requested.group == "org.jetbrains.compose.components" &&
            requested.name.startsWith("components-resources")) {
            useVersion(composeVersion)
            because("Using forked Compose components-resources with linuxArm64 support")
        }
    }
}

kotlin {
    linuxArm64 {
        binaries {
            executable {
                entryPoint = "com.bitchat.embedded.main"
                baseName = "bitchat-embedded"
                // Link against DRM, GBM, EGL, GLESv2
                val sysrootLib = project.file("sysroot/usr/lib/aarch64-linux-gnu").absolutePath
                // Bluetooth module library paths (linkerOpts in .def propagate -l flags,
                // but -L paths must be on the binary since .def can't use relative paths)
                val btGattlibLib = project.file("../../data/remote/transport/bluetooth/native/gattlib/build/linux-arm64/install/lib").absolutePath
                val btSysrootLib = project.file("../../data/remote/transport/bluetooth/native/sysroot/lib/aarch64-linux-gnu").absolutePath
                linkerOpts(
                    "-L$sysrootLib",
                    "-L$btGattlibLib",
                    "-L$btSysrootLib",
                    "-ldrm", "-lgbm", "-lEGL", "-lGLESv2",
                    // Skia/Skiko font dependencies
                    "-lfontconfig", "-lfreetype",
                    "-lpng16", "-lz", "-lexpat", "-lbz2",
                    // Nothing to do with Skiko, whatever the old comment here said: skiko
                    // linuxarm64 ships static archives inside the klib, and this flag only relaxes
                    // symbol resolution for shared objects on the link line. What it covers is
                    // glibc version skew between the extracted Pi sysroot and the older glibc
                    // Kotlin/Native links linux_arm64 against. Dropping it fails with exactly
                    // three errors, measured:
                    //   stat64@GLIBC_2.33, fstat64@GLIBC_2.33  <- sysroot libdrm.so
                    //   pow@GLIBC_2.29                         <- sysroot libpng16.so
                    // All three resolve fine on the device (Debian bookworm, glibc 2.36), so this
                    // is a build-host artefact, not a missing symbol. libgbm.so.1 and
                    // libEGL_mesa.so.0 never even reach the check: LLD skips a DSO whose own
                    // DT_NEEDED entries are not all on the link line, and the sysroot has no
                    // libwayland-*, libxcb-* or libexpat.so.1. Keep the flag; the experiment has
                    // been run twice now.
                    "--allow-shlib-undefined",
                )
            }
        }
        val sysrootInclude = project.file("sysroot/usr/include")

        compilations.getByName("main") {
            cinterops {
                val drm by creating {
                    defFile(project.file("src/nativeInterop/cinterop/drm.def"))
                    includeDirs(sysrootInclude, project.file("sysroot/usr/include/libdrm"))
                }
                val gbm by creating {
                    defFile(project.file("src/nativeInterop/cinterop/gbm.def"))
                    includeDirs(sysrootInclude)
                }
                val egl by creating {
                    defFile(project.file("src/nativeInterop/cinterop/egl.def"))
                    includeDirs(sysrootInclude)
                    compilerOpts("-DMESA_EGL_NO_X11_HEADERS")
                }
                val gles2 by creating {
                    defFile(project.file("src/nativeInterop/cinterop/gles2.def"))
                    includeDirs(sysrootInclude)
                }
                val evdev by creating {
                    defFile(project.file("src/nativeInterop/cinterop/evdev.def"))
                    includeDirs(sysrootInclude)
                }
                val i2c by creating {
                    defFile(project.file("src/nativeInterop/cinterop/i2c.def"))
                    includeDirs(sysrootInclude)
                }
                val select by creating {
                    defFile(project.file("src/nativeInterop/cinterop/select.def"))
                    includeDirs(sysrootInclude)
                }
            }
        }
    }

    sourceSets {
        val linuxArm64Main by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)

                // Upstream Skiko from Maven Central. Since 0.9.47 the linuxarm64 artifact
                // bundles an EGL-only Skia (no GLX object at all), which is why the local
                // EGL fork was dropped -- see docs/FORKED_LIBRARIES.md.
                implementation("org.jetbrains.skiko:skiko-linuxarm64:$skikoVersion")

                implementation("org.jetbrains.compose.runtime:runtime:$composeVersion")
                implementation("org.jetbrains.compose.foundation:foundation-linuxarm64:$composeVersion")
                implementation("org.jetbrains.compose.material3:material3-linuxarm64:$composeVersion")
                implementation("org.jetbrains.compose.ui:ui-linuxarm64:$composeVersion")

                implementation("org.jetbrains.compose.foundation:foundation-layout-linuxarm64:$composeVersion")
                implementation("org.jetbrains.compose.animation:animation-linuxarm64:$composeVersion")
                implementation("org.jetbrains.compose.animation:animation-core-linuxarm64:$composeVersion")
                implementation("org.jetbrains.compose.ui:ui-geometry-linuxarm64:$composeVersion")
                implementation("org.jetbrains.compose.ui:ui-graphics-linuxarm64:$composeVersion")
                implementation("org.jetbrains.compose.ui:ui-text-linuxarm64:$composeVersion")
                implementation("org.jetbrains.compose.ui:ui-unit-linuxarm64:$composeVersion")
                implementation("org.jetbrains.compose.ui:ui-util-linuxarm64:$composeVersion")

                // Koin - explicit linuxarm64 artifacts to bypass multiplatform module resolution
                implementation("io.insert-koin:koin-core-linuxarm64:$koinVersion")
                implementation("io.insert-koin:koin-compose-linuxarm64:$koinVersion")
                implementation("io.insert-koin:koin-compose-viewmodel-linuxarm64:$koinVersion")

                // Lifecycle - explicit linuxarm64 artifacts to bypass multiplatform module resolution
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-common-linuxarm64:$composeVersion")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-linuxarm64:$composeVersion")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose-linuxarm64:$composeVersion")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-linuxarm64:$composeVersion")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-savedstate-linuxarm64:$composeVersion")

                implementation("org.jetbrains.androidx.savedstate:savedstate-linuxarm64:$composeVersion")

                // Domain for BitchatMessage model
                implementation(project(":domain"))
                implementation(project(":presentation:design"))
                implementation(project(":presentation:viewmodel"))
                implementation(project(":presentation:viewvo"))
                implementation(project(":presentation:screens"))

                // Data layer modules for real data access
                implementation(project(":data:crypto"))
                implementation(project(":data:local:platform"))
                implementation(project(":data:remote:rest:client"))
                implementation(project(":data:repo"))
                implementation(project(":data:cache"))
                implementation(project(":data:remote:transport:nostr"))
                implementation(project(":data:remote:transport:bluetooth"))
                implementation(project(":data:remote:transport:lora"))
                implementation(project(":data:remote:transport:lora:bitchat"))
                implementation(project(":data:remote:transport:lora:meshtastic"))
                implementation(project(":data:remote:transport:lora:meshcore"))
                implementation(project(":data:noise"))
                implementation(project(":data:remote:tor"))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Build identity. A Gradle task writes EmbeddedBuildInfo.kt so the binary can
// report the commit it was built from (printed at startup and by --version),
// plus a raw build-info.properties for tooling. Uses providers.exec so it stays
// configuration-cache safe. On a clean tree the generated files are a pure
// function of (version, sha, branch) and the commit time, so the task, compile
// and link stay UP-TO-DATE. On a dirty tree the build time is the wall clock
// and the task reruns every build on purpose.
//
// "Dirty" means any modified, staged, deleted or untracked file under the roots
// that feed the binary (apps, data, domain, presentation, iosdi and the root
// Gradle files). Untracked files count because an un-added source file is
// compiled into the binary just like a committed one. Gitignored paths (build/,
// sysroot/, native outputs) never show up in --porcelain, and submodule
// worktree changes are ignored (--ignore-submodules=dirty), so a clean tree is
// reported as clean.
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
val gitDirty = git(
    "status", "--porcelain", "--untracked-files=normal", "--ignore-submodules=dirty", "--",
    "apps", "data", "domain", "presentation", "iosdi",
    "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradle",
).map { it.isNotEmpty() }

val embeddedBuildInfoDir = layout.buildDirectory.dir("generated/embeddedBuildInfo")

val generateEmbeddedBuildInfo = tasks.register("generateEmbeddedBuildInfo") {
    group = "build"
    description = "Writes EmbeddedBuildInfo.kt and build-info.properties (git SHA, branch, dirty flag, build time, version)."
    val kotlinOutputDir = embeddedBuildInfoDir.map { it.dir("kotlin") }
    val propsFile = embeddedBuildInfoDir.map { it.file("build-info.properties") }
    inputs.property("version", version.toString())
    inputs.property("gitSha", gitSha)
    inputs.property("gitBranch", gitBranch)
    inputs.property("gitCommitTime", gitCommitTime)
    inputs.property("gitDirty", gitDirty)
    outputs.dir(kotlinOutputDir)
    outputs.file(propsFile)
    // Bind to a local so the serialized lambdas below capture the provider, not the script object
    // (the configuration cache cannot serialize Gradle script object references).
    val dirtyProvider = gitDirty
    outputs.upToDateWhen { !dirtyProvider.get() }
    outputs.cacheIf { !dirtyProvider.get() }
    doLast {
        // Defined here, not at script level: a script-level function would drag the script
        // object into this lambda and break the configuration cache.
        fun String.kotlinLiteral(): String = buildString {
            for (c in this@kotlinLiteral) when (c) {
                '\\' -> append("\\\\"); '"' -> append("\\\""); '$' -> append("\\$")
                '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        val props = inputs.properties
        val versionValue = props.getValue("version").toString()
        val sha = props.getValue("gitSha").toString()
        val branch = props.getValue("gitBranch").toString()
        // A newline in either value would split the identity line and leave an unparsable
        // build-info.properties line; refuse it rather than write a sidecar the deploy misreads.
        for ((key, value) in listOf("embedded.version" to versionValue, "git branch" to branch)) {
            if ('\n' in value || '\r' in value) {
                throw GradleException(
                    "embedded build info: $key must not contain a newline, got \"${value.kotlinLiteral()}\""
                )
            }
        }
        val dirty = props.getValue("gitDirty") as Boolean
        val builtAt = if (dirty) Instant.now().toString() else props.getValue("gitCommitTime").toString()
        val dir = kotlinOutputDir.get().asFile.resolve("com/bitchat/embedded")
        dir.mkdirs()
        dir.resolve("EmbeddedBuildInfo.kt").writeText(
            """
            |// Generated by :apps:embedded:generateEmbeddedBuildInfo. Do not edit.
            |package com.bitchat.embedded
            |
            |internal object EmbeddedBuildInfo {
            |    const val VERSION = "${versionValue.kotlinLiteral()}"
            |    const val GIT_SHA = "${sha.kotlinLiteral()}"
            |    const val GIT_BRANCH = "${branch.kotlinLiteral()}"
            |    const val GIT_DIRTY = $dirty
            |    const val BUILT_AT = "${builtAt.kotlinLiteral()}"
            |}
            |""".trimMargin()
        )
        // Raw (unescaped) metadata for tooling; the link tasks below copy it into the sidecar.
        propsFile.get().asFile.writeText(
            """
            |name=bitchat-embedded
            |version=$versionValue
            |git_sha=$sha
            |git_branch=$branch
            |git_dirty=$dirty
            |built_at=$builtAt
            |""".trimMargin()
        )
    }
}

kotlin.sourceSets.named("linuxArm64Main") {
    // Only kotlin/ is a source root (the task also writes build-info.properties);
    // mapping the task provider keeps the compile -> generate task dependency.
    val generatedKotlin = embeddedBuildInfoDir.map { it.dir("kotlin") }
    kotlin.srcDir(generateEmbeddedBuildInfo.map { generatedKotlin.get() })
}

// ---------------------------------------------------------------------------
// Sidecar. Every link of the bitchat-embedded executable writes
// bitchat-embedded.build-info next to the binary: the raw metadata above plus
// build=<debug|release> (from the binary's debuggable flag, see below),
// kexe_sha256=<sha256 of the executable> and identity=<line>. The identity line
// must be byte-for-byte what BuildIdentity.kt prints (`bitchat-embedded <version>
// (<sha12>, <branch>, clean|dirty, <build>, built <built_at>)`):
// scripts/deploy-pi.sh reads the sidecar, checks the executable's SHA-256 against
// it, and requires `--version` on the device to print exactly that line. Keep the
// two formats in sync.
// ---------------------------------------------------------------------------
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink>().configureEach {
    // `binary` is @Transient (configuration phase only); KGP also registers a test
    // binary (TestExecutable, baseName "test"), which must not get a sidecar.
    val nativeBinary = binary
    if (nativeBinary !is org.jetbrains.kotlin.gradle.plugin.mpp.Executable || nativeBinary.baseName != "bitchat-embedded") {
        return@configureEach
    }
    // build= follows the linker's -g flag (NativeBinary.debuggable), which is exactly what
    // Platform.isDebugBinary reports at runtime in BuildIdentity.kt. It defaults from the
    // build type but can be overridden in the DSL, so buildType.name could disagree with
    // the binary. Read at configuration time into a String (CC safe).
    val buildType = if (nativeBinary.debuggable) "debug" else "release"
    val props = layout.buildDirectory.file("generated/embeddedBuildInfo/build-info.properties")
    val kexe = destinationDirectory.file("bitchat-embedded.kexe")
    val sidecar = destinationDirectory.file("bitchat-embedded.build-info")
    inputs.file(props).withPropertyName("embeddedBuildInfo").withPathSensitivity(PathSensitivity.NONE)
    outputs.file(sidecar).withPropertyName("embeddedBuildInfoSidecar")
    doLast {
        val lines = props.get().asFile.readLines().filter { it.isNotBlank() }
        val values = lines.associate { line ->
            val eq = line.indexOf('=')
            require(eq > 0) { "malformed build-info.properties line: $line" }
            line.substring(0, eq) to line.substring(eq + 1)
        }
        val kexeFile = kexe.get().asFile
        require(kexeFile.isFile) { "expected linked executable at $kexeFile" }
        val digest = MessageDigest.getInstance("SHA-256")
        kexeFile.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val kexeSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        val identity = buildString {
            append(values.getValue("name")).append(' ').append(values.getValue("version"))
            append(" (").append(values.getValue("git_sha").take(12))
            append(", ").append(values.getValue("git_branch"))
            append(", ").append(if (values.getValue("git_dirty").toBoolean()) "dirty" else "clean")
            append(", ").append(buildType)
            append(", built ").append(values.getValue("built_at"))
            append(')')
        }
        sidecar.get().asFile.writeText(
            (lines + listOf("build=$buildType", "kexe_sha256=$kexeSha256", "identity=$identity"))
                .joinToString("\n", postfix = "\n")
        )
    }
}

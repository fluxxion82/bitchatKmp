import java.time.Instant

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Embedded-only code that does not depend on a UI toolkit, shared by the Compose app
// (:apps:embedded) and the terminal app (:apps:embedded-tui): the build identity, the Koin
// build-config module, the user-state initializer and the LoRa protocol selector. Included only
// in the embedded profile (see settings.gradle.kts).

val koinVersion = providers.gradleProperty("embedded.koinForkVersion")
    .orElse("4.2.2")
    .get()

kotlin {
    linuxArm64()

    sourceSets {
        named("linuxArm64Main") {
            dependencies {
                implementation(project(":domain"))
                implementation(project(":data:remote:transport:lora"))
                // Explicit linuxarm64 artifact to bypass multiplatform module resolution (as in :apps:embedded).
                implementation("io.insert-koin:koin-core-linuxarm64:$koinVersion")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Build identity. A Gradle task writes EmbeddedBuildInfo.kt so each embedded
// binary can report the commit it was built from (printed at startup and by
// --version), plus a raw build-info.properties for tooling; the link sidecar
// (gradle/embedded-link-sidecar.gradle.kts) copies it next to each binary, with
// the binary's own name. Uses providers.exec so it stays configuration-cache
// safe. On a clean tree the generated files are a pure function of (version,
// sha, branch) and the commit time, so the task, compile and link stay
// UP-TO-DATE. On a dirty tree the build time is the wall clock and the task
// reruns every build on purpose.
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
            |// Generated by :apps:embedded-common:generateEmbeddedBuildInfo. Do not edit.
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
        // Raw (unescaped) metadata for tooling. No name= line: the link sidecar starts with the
        // name of the binary it describes (gradle/embedded-link-sidecar.gradle.kts).
        propsFile.get().asFile.writeText(
            """
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

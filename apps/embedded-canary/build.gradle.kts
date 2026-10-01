import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Release-mode Kotlin/Native canary for the embedded binaries: tests only, built and run on the
// build host, because the embedded executables are linuxArm64 and their release builds cannot run
// here. What it guards is target independent: code shapes that an optimized Kotlin/Native build got
// wrong (see ReleaseCompilerCanaryTest), linked with the same embedded.kotlinNativeReleaseArgs as
// the release executables of :apps:embedded and :apps:embedded-tui. Run :apps:embedded-canary:hostReleaseTest
// (scripts/verify.sh embedded does). Included only in the embedded profile (see settings.gradle.kts).

val kotlinNativeReleaseArgs = providers.gradleProperty("embedded.kotlinNativeReleaseArgs")
    .orElse("")
    .get()
    .split(' ')
    .filter(String::isNotBlank)

kotlin {
    // One target, named "host" so the task names do not depend on the machine. On any other build
    // host there is no target and so no hostReleaseTest task: verify.sh embedded fails on it, but the
    // rest of the embedded profile still configures.
    val hostOs = System.getProperty("os.name")
    val hostArch = System.getProperty("os.arch")
    val host = when {
        hostOs == "Mac OS X" && hostArch == "aarch64" -> macosArm64("host")
        hostOs == "Linux" && hostArch == "amd64" -> linuxX64("host")
        else -> null
    }
    if (host == null) {
        logger.warn("embedded-canary: no Kotlin/Native host target for $hostOs/$hostArch, so no hostReleaseTest")
        return@kotlin
    }
    host.binaries.test(listOf(NativeBuildType.RELEASE)) {
        freeCompilerArgs += kotlinNativeReleaseArgs
    }
    // hostTest runs the default debug test binary; hostReleaseTest runs the release one.
    host.testRuns.create("release") {
        setExecutionSourceFrom(host.binaries.getTest(NativeBuildType.RELEASE))
    }

    sourceSets {
        named("hostTest") {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

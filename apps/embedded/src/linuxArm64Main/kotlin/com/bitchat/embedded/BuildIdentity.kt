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
    // Must match the `identity=` line the link task writes to bitchat-embedded.build-info
    // (apps/embedded/build.gradle.kts); scripts/deploy-pi.sh verifies them equal on the device.
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

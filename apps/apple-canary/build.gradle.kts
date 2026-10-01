import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Release-mode guard for KT-88544 (see apple.kotlinNativeReleaseArgs in gradle.properties). The miscompile
// only shows in an optimized link, so each target gets a release test binary, linked by the same root
// build hook as the iOS release frameworks, and a `release` test run to execute it:
//   ./gradlew --console=plain :apps:apple-canary:macosArm64ReleaseTest            # passes
//   ./gradlew --console=plain :apps:apple-canary:macosArm64ReleaseTest -Papple.kotlinNativeReleaseArgs=
//                                                                                  # fails: workaround off
// iosSimulatorArm64ReleaseTest does the same on the simulator (needs CoreSimulatorService).
kotlin {
    listOf(macosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.test(listOf(NativeBuildType.RELEASE))
        target.testRuns.create("release") {
            setExecutionSourceFrom(target.binaries.getTest(NativeBuildType.RELEASE))
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// The default debug test runs stay off. 2.4.20 miscompiles these shapes in debug links too (mostly a
// ClassCastException), but the workaround is release-only, so they would fail without guarding anything
// the release runs do not.
tasks.named { it == "macosArm64Test" || it == "iosSimulatorArm64Test" }.configureEach {
    enabled = false
}

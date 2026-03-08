plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.jetbrains.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.cocoapods) apply false
}

val embeddedEnabled = providers.gradleProperty("embedded.enabled")
    .map(String::toBoolean)
    .orElse(false)
    .get()
val composeSnapshotVersion = providers.gradleProperty("embedded.composeForkVersion")
    .orElse("9999.0.0-SNAPSHOT")
    .get()
val embeddedKoinVersion = providers.gradleProperty("embedded.koinForkVersion")
    .orElse("4.1.2")
    .get()

subprojects {
    configurations.all {
        // Exclude webview - not supported on linuxArm64
        exclude(group = "io.github.kevinnzou", module = "compose-webview-multiplatform")

        if (embeddedEnabled) {
            resolutionStrategy.eachDependency {
                val composeGroups = listOf(
                    "org.jetbrains.compose.ui",
                    "org.jetbrains.compose.foundation",
                    "org.jetbrains.compose.material",
                    "org.jetbrains.compose.material3",
                    "org.jetbrains.compose.animation",
                    "org.jetbrains.compose.runtime"
                )
                if (requested.group in composeGroups) {
                    useVersion(composeSnapshotVersion)
                    because("Using forked Compose with linuxArm64 support")
                }
                if (requested.group == "org.jetbrains.compose.components" &&
                    requested.name.startsWith("components-resources")) {
                    useVersion(composeSnapshotVersion)
                    because("Using forked Compose components-resources with linuxArm64 support")
                }
                // Force forked lifecycle with linuxArm64 support
                if (requested.group == "org.jetbrains.androidx.lifecycle") {
                    useVersion(composeSnapshotVersion)
                    because("Using forked lifecycle with linuxArm64 support")
                }
                // Force forked savedstate with linuxArm64 support
                if (requested.group == "org.jetbrains.androidx.savedstate") {
                    useVersion(composeSnapshotVersion)
                    because("Using forked savedstate with linuxArm64 support")
                }
                // Force forked Koin with linuxArm64 support
                if (requested.group == "io.insert-koin") {
                    useVersion(embeddedKoinVersion)
                    because("Using forked Koin with linuxArm64 support")
                }
            }
        }
    }

    // Add opt-in for ExperimentalTime API (for all Kotlin compilations including Native)
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
        compilerOptions {
            freeCompilerArgs.add("-opt-in=kotlin.time.ExperimentalTime")
        }
    }
}

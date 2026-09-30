plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
}

version = "0.0.1"

val embeddedEnabled = providers.gradleProperty("embedded.enabled")
    .map(String::toBoolean)
    .orElse(false)
    .get()

// Mosaic composables for the terminal UIs. The module is included only when embedded.enabled=true or
// tui.enabled=true (Mosaic resolves from mavenLocal); linuxArm64 is declared only for the embedded profile.
// It takes plain state and lambdas, never ViewModels or Koin, so every screen is tested on the JVM.
//
// Terminal width assumptions (by design; see sanitizePeerText in PeerText.kt):
// - Mosaic's bundled Unicode width table is authoritative for layout.
// - East Asian Ambiguous characters are one cell.
// - Peer text is sanitized before it is measured, removing the sequences terminals disagree on
//   (emoji ZWJ/skin-tone/keycap/tag sequences, detached marks, conjoining jamo, and so on).
// - Terminals on another Unicode version or in an ambiguous-wide mode may misalign by design.
// - On the Linux console, console-safe mode (TERM=linux) is the real guarantee: one cell per character.
kotlin {
    applyDefaultHierarchyTemplate()
    jvm()
    if (embeddedEnabled) {
        linuxArm64()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":domain"))
                implementation(project(":presentation:viewvo"))
                implementation(libs.mosaic.runtime)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(libs.mosaic.testing)
                implementation(libs.kotlin.test)
                implementation(libs.kotlin.test.junit)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

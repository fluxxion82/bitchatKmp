plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
}

version = "0.0.1"

// Mosaic composables for the embedded terminal UI. The module is included only when
// embedded.enabled=true (Mosaic resolves from mavenLocal), so linuxArm64 needs no extra gate.
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
    linuxArm64()

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

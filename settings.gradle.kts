pluginManagement {
    val embeddedEnabled = settings.providers.gradleProperty("embedded.enabled")
        .map(String::toBoolean)
        .orElse(false)
        .get()
    val composeForkVersion = settings.providers.gradleProperty("embedded.composeForkVersion")
        .orElse("1.12.1-embedded-SNAPSHOT")
        .get()

    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        gradlePluginPortal()
        mavenCentral()
        if (embeddedEnabled) {
            mavenLocal()
        }
        maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/dev")
    }

    // Provide the Compose Multiplatform plugin version here (catalog entry is versionless)
    // so that embedded builds can use the fork version while standard builds use 1.12.1.
    val composeVersion = if (embeddedEnabled) composeForkVersion else "1.12.1"
    plugins {
        id("org.jetbrains.compose") version composeVersion
    }
}

val embeddedEnabled = providers.gradleProperty("embedded.enabled")
    .map(String::toBoolean)
    .orElse(false)
    .get()

// Desktop terminal UI profile (opt-in, JVM only): adds :presentation:tui and :apps:desktop-tui and lets
// the Mosaic fork resolve from mavenLocal. Unlike embedded.enabled it forces no fork versions.
val tuiEnabled = providers.gradleProperty("tui.enabled")
    .map(String::toBoolean)
    .orElse(false)
    .get()
if (tuiEnabled && embeddedEnabled) {
    throw GradleException(
        "tui.enabled is the desktop profile; pass -Pembedded.enabled=false " +
            "(embedded.enabled swaps the JVM graph to fork snapshots)"
    )
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        if (tuiEnabled) {
            // Only the Mosaic fork comes from mavenLocal; everything else resolves as in a flagless build.
            mavenLocal {
                content { includeGroup("com.jakewharton.mosaic") }
            }
        }
        if (embeddedEnabled) {
            // mavenLocal first for forked libs (Koin, Compose with linuxArm64).
            // Skiko is NOT here: it resolves from mavenCentral (see docs/FORKED_LIBRARIES.md).
            mavenLocal()
        }
        google()
        mavenCentral()
        maven("https://jogamp.org/deployment/maven")
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven("https://maven.pkg.jetbrains.space/public/p/ktor/eap")
        maven("https://maven.pkg.jetbrains.space/kotlin/p/wasm/experimental")
        maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/dev")
        maven("https://jitpack.io")  // For usb-serial-for-android
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "bitchatKmp"

include(":apps:droid")
include(":apps:apple-canary")
include(":apps:desktop")
include(":apps:desktop-common")
include(":data:cache")
include(":data:crypto")
include(":data:local:platform")
include(":data:mediautils")
include(":data:noise")
include(":data:remote:rest:client")
include(":data:remote:rest:dto")
include(":data:remote:transport")
include(":data:remote:transport:bluetooth")
include(":data:remote:transport:lora")
include(":data:remote:transport:lora:bitchat")
include(":data:remote:transport:lora:meshtastic")
include(":data:remote:transport:lora:meshcore")
include(":data:remote:transport:nostr")
include(":data:remote:tor")
include(":data:repo")
include(":domain")
include(":iosdi")
include(":presentation:design")
include(":presentation:design:imagepicker")
include(":presentation:screens")
include(":presentation:viewmodel")
include(":presentation:viewvo")
if (embeddedEnabled) {
    include(":apps:embedded")
    include(":apps:embedded-canary")
    include(":apps:embedded-common")
    include(":apps:embedded-tui")
    include(":presentation:tui")
    include(":presentation:tui:binding")
}
if (tuiEnabled) {
    include(":presentation:tui")
    include(":presentation:tui:binding")
    include(":apps:desktop-tui")
}

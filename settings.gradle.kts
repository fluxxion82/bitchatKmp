pluginManagement {
    val embeddedEnabled = settings.providers.gradleProperty("embedded.enabled")
        .map(String::toBoolean)
        .orElse(false)
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
}

val embeddedEnabled = providers.gradleProperty("embedded.enabled")
    .map(String::toBoolean)
    .orElse(false)
    .get()

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        if (embeddedEnabled) {
            // mavenLocal first for forked libs (Koin, Compose with linuxArm64)
            mavenLocal()
            // Jake Wharton's custom EGL-enabled Skiko for embedded Linux ARM64
            maven {
                url = uri("${rootDir.absolutePath}/apps/embedded/maven")
            }
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
include(":apps:desktop")
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
include(":data:remote:transport")
include(":domain")
include(":iosdi")
include(":presentation:design")
include(":presentation:design:imagepicker")
include(":presentation:screens")
include(":presentation:viewmodel")
include(":presentation:viewvo")
if (embeddedEnabled) {
    include(":apps:embedded")
}

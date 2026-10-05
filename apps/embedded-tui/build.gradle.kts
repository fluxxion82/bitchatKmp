import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
}

val koinVersion = providers.gradleProperty("embedded.koinForkVersion")
    .orElse("4.2.2-embedded-SNAPSHOT")
    .get()
// Compiler workarounds for the release link (KT-88544; see gradle.properties).
val kotlinNativeReleaseArgs = providers.gradleProperty("embedded.kotlinNativeReleaseArgs")
    .orElse("")
    .get()
    .split(' ')
    .filter(String::isNotBlank)

// The bitchat terminal UI for the Orange Pi: the :presentation:tui screens on Mosaic, bound to the
// same view models and data layer as the Compose app (:apps:embedded), without Compose UI, Skiko
// or the DRM/GBM/EGL stack. Included only in the embedded profile (see settings.gradle.kts).

kotlin {
    linuxArm64 {
        binaries.executable {
            baseName = "bitchat-tui"
            entryPoint = "com.bitchat.tui.app.main"
            if (buildType == NativeBuildType.RELEASE) {
                freeCompilerArgs += kotlinNativeReleaseArgs
            }
            // The Bluetooth module links gattlib and its GLib/D-Bus/BlueZ dependencies from static
            // archives; a .def cannot hold relative -L paths, so they go on the binary (as in
            // :apps:embedded). Nothing here links DRM, GBM, EGL or Skia.
            val btGattlibLib = project.file("../../data/remote/transport/bluetooth/native/gattlib/build/linux-arm64/install/lib").absolutePath
            val btSysrootLib = project.file("../../data/remote/transport/bluetooth/native/sysroot/lib/aarch64-linux-gnu").absolutePath
            linkerOpts("-L$btGattlibLib", "-L$btSysrootLib")
        }
    }

    sourceSets {
        named("linuxArm64Main") {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation("io.insert-koin:koin-core-linuxarm64:$koinVersion")

                implementation(project(":apps:embedded-common"))
                implementation(project(":presentation:tui"))
                implementation(project(":presentation:tui:binding"))

                // The data layer, as in :apps:embedded.
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

// Every link writes bitchat-tui.build-info next to the binary (identity from :apps:embedded-common).
extra["embeddedBinaryBaseName"] = "bitchat-tui"
apply(from = rootProject.file("gradle/embedded-link-sidecar.gradle.kts"))

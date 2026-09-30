plugins {
    kotlin("jvm")
    alias(libs.plugins.compose.compiler)
    application
}

// The bitchat terminal UI for the desktop (JVM): the :presentation:tui screens on Mosaic, bound to the
// same data layer as :apps:desktop, without Compose UI or Skiko. Included only with -Ptui.enabled=true
// (see settings.gradle.kts). Mosaic opens the controlling tty, not stdout, so run the installDist
// launcher from a real terminal; `run` cannot work.

version = "0.0.1"

val generatedBuildInfo = layout.buildDirectory.dir("generated/desktopTuiBuildInfo")
val gitSha = providers.exec {
    workingDir(rootProject.projectDir)
    commandLine("git", "rev-parse", "--short", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }
val generateDesktopTuiBuildInfo = tasks.register("generateDesktopTuiBuildInfo") {
    val kotlinOutputDir = generatedBuildInfo
    inputs.property("version", project.version.toString())
    inputs.property("gitSha", gitSha)
    outputs.dir(kotlinOutputDir)
    doLast {
        val properties = inputs.properties
        val output = kotlinOutputDir.get().asFile.resolve("com/bitchat/desktop/tui/DesktopTuiBuildInfo.kt")
        output.parentFile.mkdirs()
        output.writeText(
            """
            |package com.bitchat.desktop.tui
            |
            |internal object DesktopTuiBuildInfo {
            |    const val version = "${properties.getValue("version")}"
            |    const val gitSha = "${properties.getValue("gitSha")}"
            |}
            |""".trimMargin(),
        )
    }
}

sourceSets.main {
    kotlin.srcDir(generatedBuildInfo)
}
tasks.named("compileKotlin") { dependsOn(generateDesktopTuiBuildInfo) }

val artiNativeDir = rootProject.layout.projectDirectory.dir("data/remote/tor/native/libs/desktop")

// macOS native BLE and location libraries, opt-in exactly as in apps/desktop: -PbleNative=macos and
// -PlocationNative=macos link the Kotlin/Native dylibs, copy them into the jar resources and pass
// -Dble.native / -Dlocation.native to the launcher (see NativeBleLoader and NativeLocationLoader).
val bleNativeProp = (findProperty("bleNative") as? String)?.lowercase()
val locationNativeProp = (findProperty("locationNative") as? String)?.lowercase()
val arch = System.getProperty("os.arch")

dependencies {
    // Mosaic composables and the ViewModel/data graph the real app will bind; see settings.gradle.kts.
    implementation(project(":apps:desktop-common"))
    implementation(project(":presentation:tui"))
    implementation(project(":presentation:tui:binding"))
    implementation(project(":presentation:viewmodel"))
    implementation(project(":presentation:viewvo"))
    implementation(project(":domain"))

    implementation(project(":data:repo"))
    implementation(project(":data:remote:rest:client"))
    implementation(project(":data:remote:transport:bluetooth"))
    implementation(project(":data:local:platform"))
    implementation(project(":data:remote:transport:nostr"))
    implementation(project(":data:remote:transport:lora"))
    implementation(project(":data:remote:transport:lora:bitchat"))
    implementation(project(":data:remote:transport:lora:meshtastic"))
    implementation(project(":data:remote:tor"))

    implementation(libs.mosaic.runtime)
    implementation(libs.koin.core)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jna)
    // The app picks the SLF4J backend, not the libraries (dbus-java logs through SLF4J).
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}

application {
    applicationName = "bitchat-tui"
    mainClass.set("com.bitchat.desktop.tui.MainKt")
    applicationDefaultJvmArgs = buildList {
        add("-Djava.awt.headless=true")
        add("-Dapple.awt.UIElement=true")
        if (bleNativeProp != null) add("-Dble.native=$bleNativeProp")
        if (locationNativeProp != null) add("-Dlocation.native=$locationNativeProp")
    }
}

distributions {
    main {
        contents {
            from(artiNativeDir) {
                include("*.dylib", "*.so", "*.dll")
                into("lib/native")
            }
        }
    }
}

// Optional: bundle macOS native BLE library when -PbleNative=macos (mac host only)
val enableNativeBle = bleNativeProp == "macos"
if (enableNativeBle && org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
    val bleProject = project(":data:remote:transport:bluetooth")
    val nativeLibDir = when {
        arch.contains("aarch64") || arch.contains("arm64") ->
            bleProject.layout.buildDirectory.dir("bin/macosArm64/debugShared")

        else ->
            bleProject.layout.buildDirectory.dir("bin/macosX64/debugShared")
    }
    val copyNativeBle = tasks.register<Copy>("copyNativeBle") {
        val libDir = nativeLibDir.get().asFile
        val libFile = libDir.resolve("libbitchat_ble.dylib")
        from(libFile)
        into(layout.buildDirectory.dir("resources/main/native/macos"))
        // Always copy to ensure updated symbols
        outputs.upToDateWhen { false }
    }
    // Ensure the native lib is built before copy
    val linkTaskName = when {
        arch.contains("aarch64") || arch.contains("arm64") ->
            ":data:remote:transport:bluetooth:linkDebugSharedMacosArm64"

        else ->
            ":data:remote:transport:bluetooth:linkDebugSharedMacosX64"
    }
    tasks.named("processResources") {
        dependsOn(copyNativeBle)
        dependsOn(linkTaskName)
    }
    // Guard in case the application plugin renames/omits the run task in some setups
    tasks.matching { it.name == "run" }.configureEach {
        dependsOn(copyNativeBle)
        // Forward Gradle property to runtime so NativeBleLoader sees it
        if (findProperty("bleNative") != null && this is JavaExec) {
            val propValue = findProperty("bleNative").toString()
            jvmArgs("-Dble.native=$propValue")
        }
    }
}

// Optional: bundle macOS native Location library when -PlocationNative=macos (mac host only)
val enableNativeLocation = locationNativeProp == "macos"
if (enableNativeLocation && org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
    val localPlatformProject = project(":data:local:platform")
    val nativeLocationLibDir = when {
        arch.contains("aarch64") || arch.contains("arm64") ->
            localPlatformProject.layout.buildDirectory.dir("bin/macosArm64/debugShared")
        else ->
            localPlatformProject.layout.buildDirectory.dir("bin/macosX64/debugShared")
    }
    val copyNativeLocation = tasks.register<Copy>("copyNativeLocation") {
        val libDir = nativeLocationLibDir.get().asFile
        val libFile = libDir.resolve("libbitchat_location.dylib")
        from(libFile)
        into(layout.buildDirectory.dir("resources/main/native/macos"))
        outputs.upToDateWhen { false }
    }
    val locationLinkTaskName = when {
        arch.contains("aarch64") || arch.contains("arm64") ->
            ":data:local:platform:linkDebugSharedMacosArm64"
        else ->
            ":data:local:platform:linkDebugSharedMacosX64"
    }
    tasks.named("processResources") {
        dependsOn(copyNativeLocation)
        dependsOn(locationLinkTaskName)
    }
    tasks.matching { it.name == "run" }.configureEach {
        dependsOn(copyNativeLocation)
        if (findProperty("locationNative") != null && this is JavaExec) {
            val propValue = findProperty("locationNative").toString()
            jvmArgs("-Dlocation.native=$propValue")
        }
    }
}

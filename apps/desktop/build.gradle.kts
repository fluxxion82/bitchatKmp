import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
}

version = "1.0.0"

val bleNativeProp = (findProperty("bleNative") as? String)?.lowercase()
val locationNativeProp = (findProperty("locationNative") as? String)?.lowercase()
val currentOs = org.gradle.internal.os.OperatingSystem.current()

compose.desktop {
    application {
        mainClass = "com.bitchat.desktop.AppKt"

        // Native library path for Arti (Tor)
        jvmArgs += listOf(
            "-Djava.library.path=${rootProject.projectDir}/data/remote/tor/native/libs/desktop"
        )

        // KCEF (Chromium) required flags
        jvmArgs += listOf(
            "--add-opens", "java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens", "java.desktop/java.awt.peer=ALL-UNNAMED"
        )
        if (currentOs.isMacOsX) {
            jvmArgs += listOf(
                "--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED",
                "--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED"
            )
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "bitchat"
            packageVersion = "1.0.0"

            macOS {
                iconFile.set(project.file("src/main/resources/icons/icon.icns"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSLocationWhenInUseUsageDescription</key>
                        <string>Bitchat needs your location to find nearby chat channels.</string>
                        <key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
                        <string>Bitchat needs your location to find nearby chat channels.</string>
                    """
                }
            }
            windows {
                iconFile.set(project.file("src/main/resources/icons/icon.ico"))
            }
            linux {
                iconFile.set(project.file("src/main/resources/icons/ic_launcher.png"))
            }
        }
        if (bleNativeProp != null) {
            jvmArgs += listOf("-Dble.native=$bleNativeProp")
        }
        if (locationNativeProp != null) {
            jvmArgs += listOf("-Dlocation.native=$locationNativeProp")
        }
    }
}

val arch = System.getProperty("os.arch")
val isArm = arch.contains("aarch64") || arch.contains("arm64")

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(project(":domain"))
    implementation(project(":data:remote:rest:client"))
    implementation(project(":data:remote:transport:bluetooth"))
    implementation(project(":data:local:platform"))
    implementation(project(":data:remote:transport:nostr"))
    implementation(project(":data:remote:tor"))
    implementation(project(":data:repo"))
    implementation(project(":presentation:design"))
    implementation(project(":presentation:screens"))
    implementation(project(":presentation:viewmodel"))

    implementation(libs.koin.core)
    implementation(libs.koin.compose)
}

tasks.test {
    useJUnitPlatform()
}

// Optional: bundle macOS native BLE library when -PbleNative=macos (mac host only)
val enableNativeBle = bleNativeProp == "macos"
if (enableNativeBle && org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
    val arch = System.getProperty("os.arch")
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
    // Guard in case the compose plugin renames/omits the run task in some setups
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

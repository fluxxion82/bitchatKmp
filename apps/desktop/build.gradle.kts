import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
}

version = "1.0.0"

val bleNativeProp = (findProperty("bleNative") as? String)?.lowercase()
// Log levels for the SLF4J loggers configured in src/main/resources/logback.xml. JavaExec forks its
// own JVM and does not inherit -D from the Gradle command line, so they arrive as -P and are passed
// on explicitly. -PbleLevel=DEBUG recovers the scanner's snapshot lines; -PbleDbusLevel=TRACE gives
// dbus-java's wire trace, which needs no root privileges.
val bleLevelProp = (findProperty("bleLevel") as? String)?.uppercase()
val bleDbusLevelProp = (findProperty("bleDbusLevel") as? String)?.uppercase()
val locationNativeProp = (findProperty("locationNative") as? String)?.lowercase()
val currentOs = org.gradle.internal.os.OperatingSystem.current()

// Where data/remote/tor/native/build-desktop.sh drops the Arti JNI library for the build host
// (libarti_desktop.dylib / .so / .dll). It is optional: absent it, Tor reports itself unavailable.
val artiNativeDir = rootProject.layout.projectDirectory.dir("data/remote/tor/native/libs/desktop")

// Compose app-resources layout: <root>/common is shipped on every OS, <root>/{macos,linux,windows}
// and <root>/<os>-<arch> only on the matching host. prepareAppResources flattens common plus the
// host's own two directories into a single staging directory, so where a library sits here decides
// which packages it is *shipped* in, not where it is found at runtime - it lands at the top level
// of -Dcompose.application.resources.dir either way, for `run` (a staged directory under
// build/compose/tmp/prepareAppResources) and for an installed package ($APPDIR/resources) alike.
// TorManager loads the Arti library from there, so nothing depends on a build-machine absolute path.
val appResourcesRoot = layout.buildDirectory.dir("bitchatAppResources")

// Split by extension rather than staging everything into common/: build-desktop.sh writes into one
// directory, and a developer who has built for more than one host would otherwise ship their macOS
// dylib inside the .deb and their Linux .so inside the .dmg.
val stageAppResources = tasks.register<Sync>("stageAppResources") {
    description = "Stages host-native libraries into the Compose app-resources layout."
    from(artiNativeDir) {
        include("*.dylib")
        into("macos")
    }
    from(artiNativeDir) {
        include("*.so")
        into("linux")
    }
    from(artiNativeDir) {
        include("*.dll")
        into("windows")
    }
    into(appResourcesRoot)
}

// Compose's own Sync reads appResourcesRootDir; it has no way to know we generate that tree.
// tasks.named, not tasks.matching: if Compose ever renames the task, this has to fail the build
// loudly rather than quietly produce a package with no Arti library in it. It has to run inside
// afterEvaluate because the Compose plugin registers prepareAppResources from its own
// afterEvaluate hook, which is registered when the plugin is applied and so runs before this one.
afterEvaluate {
    tasks.named<Sync>("prepareAppResources") {
        dependsOn(stageAppResources)
    }
}

compose.desktop {
    application {
        mainClass = "com.bitchat.desktop.AppKt"

        nativeDistributions {
            // jpackage only emits host-OS formats, so Deb/Rpm must be built on Linux
            // (and Rpm additionally needs the `rpm-build` package installed).
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "bitchat"
            packageVersion = "1.0.0"
            appResourcesRootDir.set(appResourcesRoot)

            macOS {
                iconFile.set(project.file("src/main/resources/ic_launcher.icns"))
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
                iconFile.set(project.file("src/main/resources/ic_launcher.ico"))
            }
            linux {
                iconFile.set(project.file("src/main/resources/ic_launcher.png"))
            }
        }
        if (bleNativeProp != null) {
            jvmArgs += listOf("-Dble.native=$bleNativeProp")
        }
        if (bleLevelProp != null) {
            jvmArgs += listOf("-Dble.level=$bleLevelProp")
        }
        if (bleDbusLevelProp != null) {
            jvmArgs += listOf("-Dble.dbus.level=$bleDbusLevelProp")
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
    // The app picks the SLF4J backend, not the libraries. :data:remote:transport:bluetooth brings
    // dbus-java, which logs through SLF4J and is silent without a binding on the classpath.
    runtimeOnly(libs.logback.classic)
    implementation(project(":domain"))
    implementation(project(":data:remote:rest:client"))
    implementation(project(":data:remote:transport:bluetooth"))
    implementation(project(":data:local:platform"))
    implementation(project(":data:remote:transport:nostr"))
    implementation(project(":data:remote:transport:lora"))
    implementation(project(":data:remote:transport:lora:bitchat"))
    implementation(project(":data:remote:transport:lora:meshtastic"))
    implementation(project(":data:remote:tor"))
    implementation(project(":data:repo"))
    implementation(project(":presentation:design"))
    implementation(project(":presentation:screens"))
    implementation(project(":presentation:viewmodel"))

    implementation(libs.koin.core)
    implementation(libs.koin.compose)
    compileOnly(libs.lifecycle.viewmodel)
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

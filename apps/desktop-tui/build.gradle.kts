import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.jvm.application.tasks.CreateStartScripts
import org.gradle.jvm.tasks.Jar
import java.nio.file.Files

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
val preparedRuntimeLibs = layout.buildDirectory.dir("preparedRuntimeLibs")
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

fun distributionJarName(artifact: ResolvedArtifactResult): String {
    val component = artifact.id.componentIdentifier
    val prefix = when (component) {
        is ModuleComponentIdentifier -> "${component.group}-${component.module}-${component.version}"
        is ProjectComponentIdentifier -> "project-${component.projectPath.replace(':', '-')}"
        else -> component.displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
    return "$prefix-${artifact.file.name}"
}

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

val runtimeArtifacts = configurations.getByName("runtimeClasspath").incoming.artifactView {}.artifacts
val runtimeArtifactsWithNames = providers.provider {
    runtimeArtifacts.artifacts
        .filter { it.file.extension == "jar" }
        .associateWith(::distributionJarName)
}
val runtimeJarNames = providers.provider {
    runtimeArtifactsWithNames.get().mapKeys { (artifact, _) -> artifact.file }
}
val applicationJar = tasks.named<Jar>("jar")
val applicationJarName = applicationJar.flatMap { it.archiveFile }
    .map { "project-apps-desktop-tui-${it.asFile.name}" }
val distributionJarNames = providers.provider {
    runtimeJarNames.get() + mapOf(applicationJar.get().archiveFile.get().asFile to applicationJarName.get())
}

val prepareRuntimeLibs = tasks.register<Sync>("prepareRuntimeLibs") {
    description = "Copies runtime jars with coordinate-prefixed filenames for the desktop distribution."
    from(applicationJar) {
        rename { applicationJarName.get() }
    }
    from(runtimeArtifacts.artifactFiles) {
        eachFile {
            if (file.extension == "jar") {
                name = runtimeJarNames.get()[file]
                    ?: throw GradleException("No distribution filename for runtime artifact $file")
            }
        }
    }
    into(preparedRuntimeLibs)
}

val verifyRuntimeJarNames = tasks.register("verifyRuntimeJarNames") {
    group = "verification"
    description = "Verifies that the desktop distribution has unique, complete runtime jars and launcher classpath."
    dependsOn("installDist")
    inputs.files(runtimeArtifacts.artifactFiles)
    inputs.dir(layout.buildDirectory.dir("install/bitchat-tui/lib"))
    doLast {
        val jarNames = distributionJarNames.get()
        val collisions = jarNames.values
            .groupBy { it }
            .filterValues { it.size > 1 }
        if (collisions.isNotEmpty()) {
            throw GradleException("Runtime artifacts map to duplicate distribution filenames: ${collisions.keys}")
        }

        val libDir = layout.buildDirectory.dir("install/bitchat-tui/lib").get().asFile
        val missing = jarNames.filter { (source, name) ->
            val destination = libDir.resolve(name)
            !destination.isFile || Files.mismatch(source.toPath(), destination.toPath()) != -1L
        }
        if (missing.isNotEmpty()) {
            throw GradleException("Distribution is missing runtime jars: ${missing.values}")
        }

        val launcher = layout.buildDirectory.file("install/bitchat-tui/bin/bitchat-tui").get().asFile.readText()
        if ("lib/*" !in launcher) {
            throw GradleException("Unix launcher does not use the lib/* classpath wildcard")
        }

        val windowsLauncher = layout.buildDirectory
            .file("install/bitchat-tui/bin/bitchat-tui.bat")
            .get()
            .asFile
            .readLines()
        val classpathLine = windowsLauncher.singleOrNull { it.startsWith("set CLASSPATH=") }
            ?: throw GradleException("Windows launcher has no CLASSPATH line")
        if ("%APP_HOME%\\lib\\*" !in classpathLine) {
            throw GradleException("Windows launcher does not use the lib\\* classpath wildcard")
        }
        check(classpathLine.length < 8191) {
            "Windows launcher CLASSPATH line exceeds the CMD 8191-character limit: ${classpathLine.length}"
        }
    }
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

tasks.named<CreateStartScripts>("startScripts") {
    dependsOn(prepareRuntimeLibs)
    classpath = files(project.file("lib/*"))
}

distributions {
    main {
        contents {
            eachFile {
                if (path.startsWith("lib/") && file.extension == "jar") {
                    name = distributionJarNames.get()[file]
                        ?: throw GradleException("No distribution filename for runtime artifact $file")
                }
            }
            from(artiNativeDir) {
                include("*.dylib", "*.so", "*.dll")
                into("lib/native")
            }
        }
    }
}

// Optional: bundle macOS native BLE library when -PbleNative=macos (Apple Silicon hosts only)
val enableNativeBle = bleNativeProp == "macos"
val isMacosArm64 = arch.contains("aarch64") || arch.contains("arm64")
if (enableNativeBle && org.gradle.internal.os.OperatingSystem.current().isMacOsX && isMacosArm64) {
    val bleProject = project(":data:remote:transport:bluetooth")
    val nativeLibDir = bleProject.layout.buildDirectory.dir("bin/macosArm64/debugShared")
    val copyNativeBle = tasks.register<Copy>("copyNativeBle") {
        val libDir = nativeLibDir.get().asFile
        val libFile = libDir.resolve("libbitchat_ble.dylib")
        from(libFile)
        into(layout.buildDirectory.dir("resources/main/native/macos"))
        // Always copy to ensure updated symbols
        outputs.upToDateWhen { false }
    }
    // Ensure the native lib is built before copy
    val linkTaskName = ":data:remote:transport:bluetooth:linkDebugSharedMacosArm64"
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

// Optional: bundle macOS native Location library when -PlocationNative=macos (Apple Silicon hosts only)
val enableNativeLocation = locationNativeProp == "macos"
if (enableNativeLocation && org.gradle.internal.os.OperatingSystem.current().isMacOsX && isMacosArm64) {
    val localPlatformProject = project(":data:local:platform")
    val nativeLocationLibDir = localPlatformProject.layout.buildDirectory.dir("bin/macosArm64/debugShared")
    val copyNativeLocation = tasks.register<Copy>("copyNativeLocation") {
        val libDir = nativeLocationLibDir.get().asFile
        val libFile = libDir.resolve("libbitchat_location.dylib")
        from(libFile)
        into(layout.buildDirectory.dir("resources/main/native/macos"))
        outputs.upToDateWhen { false }
    }
    val locationLinkTaskName = ":data:local:platform:linkDebugSharedMacosArm64"
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

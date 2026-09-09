plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

version = "0.0.1"
val embeddedEnabled = providers.gradleProperty("embedded.enabled")
    .map(String::toBoolean)
    .orElse(false)
    .get()

kotlin {
    applyDefaultHierarchyTemplate()

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            binaryOption("bundleId", "bluetooth")
            transitiveExport = true

            baseName = "bluetooth"
        }
    }
    val macosX64 = macosX64()
    val macosArm64 = macosArm64()
    listOf(macosX64, macosArm64).forEach { target ->
        target.binaries {
            sharedLib {
                baseName = "bitchat_ble"
            }
        }
    }

    if (embeddedEnabled) {
        // Linux ARM64 target for embedded devices with BlueZ BLE support
        linuxArm64 {
            val gattlibLibDir = "${project.projectDir}/native/gattlib/build/linux-arm64/install/lib"
            val sysrootLib = "${project.projectDir}/native/sysroot/lib/aarch64-linux-gnu"

            compilations.getByName("main") {
                cinterops {
                    val gattlib by creating {
                        defFile(project.file("src/nativeInterop/cinterop/gattlib.def"))
                        includeDirs(
                            project.file("native/gattlib/include"),
                            project.file("native/include")
                        )
                        compilerOpts("-DBLUEZ_VERSION_MAJOR=5")
                        extraOpts("-libraryPath", gattlibLibDir)
                        extraOpts("-libraryPath", sysrootLib)
                    }
                    val dbus by creating {
                        defFile(project.file("src/nativeInterop/cinterop/dbus.def"))
                        includeDirs(project.file("native/include/dbus-1.0"))
                        extraOpts("-libraryPath", sysrootLib)
                    }
                    val glib by creating {
                        defFile(project.file("src/nativeInterop/cinterop/glib.def"))
                        includeDirs(
                            project.file("native/sysroot/usr/include/glib-2.0"),
                            project.file("native/sysroot/usr/lib/aarch64-linux-gnu/glib-2.0/include")
                        )
                    }
                }
            }
        }
    }

    jvm("desktop")
    androidLibrary {
        namespace = "com.bitchat.ble"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
        withHostTestBuilder {}.configure {}
    }
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":domain"))
                implementation(project(":data:remote:rest:dto"))
                implementation(project(":data:cache"))
                implementation(project(":data:crypto"))
                implementation(project(":data:noise"))
                implementation(project(":data:local:platform"))

                implementation(libs.koin.core)
                implementation(libs.kotlinx.serialization)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val commonTest by getting {
            dependencies { }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.koin.android)
                // implementation(libs.bouncy.castle.bcprov)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.jna)

                // BlueZ over D-Bus: the org.bluez API is reached through the system bus, which
                // dbus-java speaks over an AF_UNIX socket. Only the SLF4J facade belongs here --
                // a library that ships a binding forces one on every consumer and races whatever
                // the app configured. The backend lives in :apps:desktop, and the spike gets its
                // own through the bleSpikeRuntime configuration below.
                implementation(libs.dbus.java.core)
                implementation(libs.dbus.java.transport.native.unixsocket)
                implementation(libs.slf4j.api)
            }
        }
        // JVM home for the tests of the pure logic in commonMain: the handshake deadline and the
        // GATT-server client registry. Running them here keeps them off the Android toolchain.
        val desktopTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlin.test.junit)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val androidHostTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlin.test.junit)
                implementation(libs.kotlinx.coroutines.test)

                implementation(libs.bcprov)

                implementation(libs.mockk.common)
                implementation(libs.mockk)
                implementation(libs.mockk.agent.jvm)
                implementation(libs.mockito.kotlin)
                implementation(libs.mockito.core)
                implementation(libs.junit)

                implementation(libs.assertj.core)
            }
        }

        val iosMain by getting
        val iosTest by getting

        val appleMain by getting
        val desktopMacMain by creating {
            dependsOn(appleMain)
        }
        val macosX64Main by getting {
            dependsOn(desktopMacMain)
        }
        val macosArm64Main by getting {
            dependsOn(desktopMacMain)
        }
    }
}

// Runs the BlueZ D-Bus spike straight off the desktop compilation, without building a jar or a
// distribution. `--args` is JavaExec's own option, so arguments pass through unchanged:
//   ./gradlew :data:remote:transport:bluetooth:bleSpike --args="scan 10"
val desktopMainCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")

// The spike needs an SLF4J backend, but the library must not ship one. This configuration exists
// only for the task's classpath, so logback stays out of the published desktop variant.
val bleSpikeRuntime: Configuration by configurations.creating

dependencies {
    bleSpikeRuntime(libs.logback.classic)
}

val bleSpike by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the BlueZ D-Bus spike against the live system bus."
    // allOutputs and runtimeDependencyFiles are both task-dependency-carrying file collections,
    // so this wires compileKotlinDesktop and desktopProcessResources in without a named dependsOn.
    classpath = files(
        desktopMainCompilation.output.allOutputs,
        desktopMainCompilation.runtimeDependencyFiles,
        bleSpikeRuntime
    )
    mainClass.set("com.bitchat.bluetooth.linux.spike.BlueZSpikeKt")
    // Kept outside any source set so it is never packaged into a consumer's classpath.
    systemProperty("logback.configurationFile", layout.projectDirectory.file("spike/logback.xml").asFile.absolutePath)
    // JavaExec forks its own JVM and does not inherit -D from the Gradle command line.
    providers.gradleProperty("bleTrace").orNull?.let { systemProperty("ble.trace", it) }
}


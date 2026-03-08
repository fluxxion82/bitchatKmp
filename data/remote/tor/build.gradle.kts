import org.jetbrains.kotlin.konan.target.KonanTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

val embeddedEnabled = providers.gradleProperty("embedded.enabled")
    .map(String::toBoolean)
    .orElse(false)
    .get()

kotlin {
    applyDefaultHierarchyTemplate()

    androidTarget()

    jvm()
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            binaryOption("bundleId", "tor")
            isStatic = true
        }

        val archDir = when (iosTarget.konanTarget) {
            KonanTarget.IOS_ARM64 -> "ios-arm64"
            KonanTarget.IOS_X64 -> "ios-x64"
            KonanTarget.IOS_SIMULATOR_ARM64 -> "ios-sim-arm64"
            else -> null
        }

        if (archDir != null) {
            val libDir = "${project.projectDir}/native/libs/$archDir/lib"

            iosTarget.compilations.get("main").cinterops {
                create("arti") {
                    includeDirs("${project.projectDir}/native/arti-ios-wrapper")
                    extraOpts("-libraryPath", libDir)
                }
            }

            iosTarget.binaries.all {
                linkerOpts(
                    "-L$libDir",
                    "-larti_ios"
                )
            }
        }
    }
    if (embeddedEnabled) {
        // Linux ARM64 target with Arti native support
        // Build native library first: ./scripts/build-native-linux-arm64.sh
        linuxArm64 {
            val archDir = "linux-arm64"
            val libDir = "${project.projectDir}/native/libs/$archDir/lib"

            compilations.get("main").cinterops {
                create("arti") {
                    defFile(project.file("src/nativeInterop/cinterop/arti-linux.def"))
                    includeDirs("${project.projectDir}/native/arti-linux-wrapper")
                    extraOpts("-libraryPath", libDir)
                }
            }

            binaries.all {
                linkerOpts(
                    "-L$libDir",
                    "-larti_linux",
                    "-lpthread",
                    "-ldl",
                    "-lm"
                )
            }
        }
    }

    listOf(
        macosX64(),
        macosArm64()
    ).forEach { macosTarget ->
        macosTarget.binaries.framework {
            binaryOption("bundleId", "tor")
            isStatic = true
        }

        val archDir = when (macosTarget.konanTarget) {
            KonanTarget.MACOS_X64 -> "macos-x64"
            KonanTarget.MACOS_ARM64 -> "macos-arm64"
            else -> null
        }

        if (archDir != null) {
            val libDir = "${project.projectDir}/native/libs/$archDir/lib"

            macosTarget.compilations.get("main").cinterops {
                create("arti") {
                    defFile(project.file("src/nativeInterop/cinterop/arti-macos.def"))
                    includeDirs("${project.projectDir}/native/arti-ios-wrapper")
                    extraOpts("-libraryPath", libDir)
                }
            }

            macosTarget.binaries.all {
                linkerOpts(
                    "-L$libDir",
                    "-larti_macos"
                )
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":domain"))

                implementation(libs.koin.core)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization)
                implementation(libs.multiplatform.settings)
                implementation(libs.kotlinx.atomicfu)
            }
        }

        val androidMain by getting {
            dependencies {
                // Android SDK Context is available by default, no additional dependencies needed
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlin.test.common)
                implementation(libs.kotlin.test.annotations.common)
                implementation(libs.kotlinx.coroutines.test)

                implementation(libs.mockk.common)
                implementation(libs.turbine)
            }
        }
        val jvmMain by getting {
            dependencies {

            }
        }
        val jvmTest by getting {
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-test")
                implementation("org.jetbrains.kotlin:kotlin-test-junit")

            }
        }
        // Apple-specific (iOS + macOS) - uses Arti native libs
        val appleMain by getting {
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }

        if (embeddedEnabled) {
            // Linux-specific - uses Arti native libs (cross-compiled)
            val linuxMain by getting {
                dependencies {
                    // Arti native library linked via cinterop
                }
            }
        }
    }
}

android {
    namespace = "com.bitchat.tor"
    compileSdk = libs.versions.compileSdk.get().toInt()
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].jniLibs.srcDirs("jniLibs")
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

// Task to check if native Arti libraries exist
val checkArtiLibraries by tasks.registering {
    group = "verification"
    description = "Check if native Arti libraries have been built"

    val projectDirPath = project.projectDir.absolutePath

    doLast {
        val baseDir = File(projectDirPath)
        val androidLibsExist = listOf("arm64-v8a", "x86_64").any { abi ->
            File(baseDir, "jniLibs/$abi/libarti_android.so").exists()
        }
        val iosLibsExist = listOf("ios-arm64", "ios-x64", "ios-sim-arm64").any { arch ->
            File(baseDir, "native/libs/$arch/lib/libarti_ios.a").exists()
        }
        val macosLibsExist = listOf("macos-arm64", "macos-x64").any { arch ->
            File(baseDir, "native/libs/$arch/lib/libarti_macos.a").exists()
        }
        val linuxLibsExist = File(baseDir, "native/libs/linux-arm64/lib/libarti_linux.a").exists()

        if (!androidLibsExist && !iosLibsExist && !macosLibsExist && !linuxLibsExist) {
            logger.warn(
                """
                ============================================================
                WARNING: Native Arti libraries not found!

                Run the build scripts to compile Arti:
                  ./native/build-android.sh  (for Android)
                  ./native/build-ios.sh      (for iOS)
                  ./native/build-macos.sh    (for macOS)
                  ./native/build-desktop.sh  (for Desktop/JVM)
                  ./native/build-all.sh      (for all platforms)

                For Linux ARM64 (cross-compiled):
                  ./scripts/build-native-linux-arm64.sh

                Or see native/ARTI_VERSION for version info.
                ============================================================
            """.trimIndent()
            )
        } else {
            logger.lifecycle("Native Arti libraries found")
            if (androidLibsExist) logger.lifecycle("  - Android libraries: ✓")
            if (iosLibsExist) logger.lifecycle("  - iOS libraries: ✓")
            if (macosLibsExist) logger.lifecycle("  - macOS libraries: ✓")
            if (linuxLibsExist) logger.lifecycle("  - Linux ARM64 libraries: ✓")
        }
    }
}

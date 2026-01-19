import org.jetbrains.kotlin.konan.target.KonanTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    applyDefaultHierarchyTemplate()
    jvm()
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            binaryOption("bundleId", "noise")
            isStatic = true
        }

        val archDir = when (iosTarget.konanTarget) {
            KonanTarget.IOS_ARM64 -> "ios-arm64"
            KonanTarget.IOS_X64 -> "ios-x64"
            KonanTarget.IOS_SIMULATOR_ARM64 -> "ios-sim-arm64"
            else -> null
        }

        if (archDir != null) {
            val noiseIncludePath = "${project.projectDir}/native/noise-c/include"
            val libDir = "${project.projectDir}/native/noise-c/build/$archDir/lib"
            val defFilePath = project.file("src/nativeInterop/cinterop/noise.def")

            iosTarget.compilations.get("main").cinterops {
                create("noise") {
                    includeDirs("native/noise-c/include")
                    extraOpts("-libraryPath", libDir)
                    linkerOpts(
                        "-L$libDir",
                        "-lnoiseprotocol",
                        "-lnoisekeys",
                        "-lnoiseprotobufs"
                    )
                }
            }

            iosTarget.binaries.all {
                // Plain linking: often enough
                linkerOpts(
                    "-L$libDir",
                    "-lnoiseprotocol",
                    "-lnoisekeys",
                    "-lnoiseprotobufs"
                )

                // If you still get undefined references at link time, swap the above three
                // for the "force load" form below (leave the -L as well):
                // linkerOpts(
                //     "-L$libDir",
                //     "-Wl,-force_load,$libDir/libnoiseprotocol.a",
                //     "-Wl,-force_load,$libDir/libnoisekeys.a",
                //     "-Wl,-force_load,$libDir/libnoiseprotobufs.a"
                // )
            }
        }
    }
    listOf(
        macosX64(),
        macosArm64()
    ).forEach { macosTarget ->
        macosTarget.binaries.framework {
            binaryOption("bundleId", "noise")
            isStatic = true
        }

        val archDir = when (macosTarget.konanTarget) {
            KonanTarget.MACOS_X64 -> "macos-x64"
            KonanTarget.MACOS_ARM64 -> "macos-arm64"
            else -> null
        }

        if (archDir != null) {
            val libDir = "${project.projectDir}/native/noise-c/build/$archDir/lib"

            macosTarget.compilations.get("main").cinterops {
                create("noise") {
                    includeDirs("native/noise-c/include")
                    extraOpts("-libraryPath", libDir)
                    linkerOpts(
                        "-L$libDir",
                        "-lnoiseprotocol",
                        "-lnoisekeys",
                        "-lnoiseprotobufs"
                    )
                }
            }

            macosTarget.binaries.all {
                linkerOpts(
                    "-L$libDir",
                    "-lnoiseprotocol",
                    "-lnoisekeys",
                    "-lnoiseprotobufs"
                )
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":domain"))
                implementation(project(":data:cache"))

                implementation(libs.koin.core)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization)


                implementation(libs.kotlinx.atomicfu)
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
                implementation("org.signal.forks:noise-java:0.1.1")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-test")
                implementation("org.jetbrains.kotlin:kotlin-test-junit")

            }
        }
        val nativeMain by getting {
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
    }
}

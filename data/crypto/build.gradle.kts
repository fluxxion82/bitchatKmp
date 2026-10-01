import org.jetbrains.kotlin.konan.target.KonanTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

val embeddedEnabled = providers.gradleProperty("embedded.enabled")
    .map(String::toBoolean)
    .orElse(false)
    .get()

kotlin {
    applyDefaultHierarchyTemplate()
    jvm()

    // Apple targets with cinterops for libsodium and secp256k1
    val iosTargets = listOf(
        iosArm64(),
        iosSimulatorArm64()
    )
    iosTargets.forEach { target ->
        target.binaries.framework {
            binaryOption("bundleId", "crypto")
            isStatic = true
        }
    }
    val macosX64 = macosX64()
    val macosArm64 = macosArm64()
    val linuxArm64Target = if (embeddedEnabled) linuxArm64() else null

    // Configure cinterops for iOS targets
    iosTargets.forEach { target ->
        val sodiumBuildDir = when (target.konanTarget) {
            KonanTarget.IOS_ARM64 -> "ios-arm64"
            KonanTarget.IOS_X64 -> "ios-x64"
            KonanTarget.IOS_SIMULATOR_ARM64 -> "ios-sim-arm64"
            else -> null
        }
        val secpBuildDir = when (target.konanTarget) {
            KonanTarget.IOS_ARM64 -> "ios-arm64"
            KonanTarget.IOS_X64 -> "ios-simulator-fat"
            KonanTarget.IOS_SIMULATOR_ARM64 -> "ios-simulator-fat"
            else -> null
        }
        if (sodiumBuildDir != null && secpBuildDir != null) {
            val sodiumHeaders = "native/libsodium/build/$sodiumBuildDir/include"
            val sodiumLib = project.file("native/libsodium/build/$sodiumBuildDir/lib").absolutePath
            val secpHeaders = "native/secp256k1/build/$secpBuildDir/include"
            val secpLib = project.file("native/secp256k1/build/$secpBuildDir/lib").absolutePath

            target.compilations.getByName("main") {
                cinterops {
                    val libsodium by creating {
                        defFile(project.file("src/nativeInterop/cinterop/libsodium.def"))
                        includeDirs(project.file(sodiumHeaders))
                        extraOpts("-libraryPath", sodiumLib)
                        extraOpts("-staticLibrary", "libsodium.a")
                    }
                    val secp256k1 by creating {
                        defFile(project.file("src/nativeInterop/cinterop/secp256k1.def"))
                        includeDirs(project.file(secpHeaders))
                        extraOpts("-libraryPath", secpLib)
                        extraOpts("-staticLibrary", "libsecp256k1.a")
                    }
                }
            }
        }
    }

    // Configure cinterops for macOS targets. Cinterop ignores linker options, so statically embed
    // the libraries to keep the dylib free of Homebrew runtime dependencies. These Homebrew paths
    // are arm64-host-only; macosX64 is not linked on this host.
    listOf(macosX64, macosArm64).forEach { target ->
        target.compilations.getByName("main") {
            cinterops {
                val libsodium by creating {
                    defFile(project.file("src/nativeInterop/cinterop/libsodium.def"))
                    includeDirs("/opt/homebrew/opt/libsodium/include")
                    extraOpts("-libraryPath", "/opt/homebrew/opt/libsodium/lib")
                    extraOpts("-staticLibrary", "libsodium.a")
                }
                val secp256k1 by creating {
                    defFile(project.file("src/nativeInterop/cinterop/secp256k1.def"))
                    includeDirs("/opt/homebrew/opt/secp256k1/include")
                    extraOpts("-libraryPath", "/opt/homebrew/opt/secp256k1/lib")
                    extraOpts("-staticLibrary", "libsecp256k1.a")
                }
            }
        }
    }

    // Configure cinterops for Linux ARM64 (if enabled)
    if (linuxArm64Target != null) {
        val sodiumLinux = "native/libsodium/build/linux-arm64"
        val secpLinux = "native/secp256k1/build/linux-arm64"

        linuxArm64Target.compilations.getByName("main") {
            cinterops {
                val libsodium by creating {
                    defFile(project.file("src/nativeInterop/cinterop/libsodium.def"))
                    includeDirs(project.file("$sodiumLinux/include"))
                    extraOpts("-libraryPath", project.file("$sodiumLinux/lib").absolutePath)
                    extraOpts("-staticLibrary", "libsodium.a")
                }
                val secp256k1 by creating {
                    defFile(project.file("src/nativeInterop/cinterop/secp256k1.def"))
                    includeDirs(project.file("$secpLinux/include"))
                    extraOpts("-libraryPath", project.file("$secpLinux/lib").absolutePath)
                    extraOpts("-staticLibrary", "libsecp256k1.a")
                }
            }
        }
    }

    androidLibrary {
        namespace = "com.bitchat.crypto"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = 21
        // The Android actual of Cryptography is its own file; without host tests nothing ever ran it.
        withHostTest {}
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":domain"))
                implementation(project(":data:cache"))

                implementation(libs.koin.core)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization)

                implementation(libs.ktor.content.negotiation)
                implementation(libs.ktor.client.logging)
                implementation(libs.ktor.kotlinx.serialization)
                implementation(libs.ktor.client.json)
                implementation(libs.ktor.client.serialization)

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
                implementation(libs.bcpg) // OpenPGP/BCPG
                implementation(libs.bcprov) // Provider
                implementation(libs.bcutil) // ASN.1 Utility Classes
                implementation(libs.bcpkix)  // PKIX/CMS/EAC/PKCS / OCSP/TSP/OPENSSL
                implementation(libs.tink)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlin.test.junit)

            }
        }
        // withHostTest runs commonTest against the Android actual. kotlin-test does not reach this
        // compilation through commonTest on its own, so name it here.
        val androidHostTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlin.test.junit)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.bcpg) // OpenPGP/BCPG
                implementation(libs.bcprov) // Provider
                implementation(libs.bcutil) // ASN.1 Utility Classes
                implementation(libs.bcpkix)  // PKIX/CMS/EAC/PKCS / OCSP/TSP/OPENSSL
                implementation(libs.tink.android)
            }
        }

        val appleMain by getting {
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }

        if (embeddedEnabled) {
            val linuxMain by getting {
                dependencies {
                    implementation(libs.ktor.client.cio)
                }
            }
        }
    }
}

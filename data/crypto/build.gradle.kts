import com.android.build.api.dsl.androidLibrary
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
        iosX64(),
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

    // Configure cinterops for macOS targets
    listOf(macosX64, macosArm64).forEach { target ->
        target.compilations.getByName("main") {
            cinterops {
                val libsodium by creating {
                    defFile(project.file("src/nativeInterop/cinterop/libsodium.def"))
                    includeDirs("/opt/homebrew/opt/libsodium/include")
                    linkerOpts("-L/opt/homebrew/opt/libsodium/lib", "-lsodium")
                }
                val secp256k1 by creating {
                    defFile(project.file("src/nativeInterop/cinterop/secp256k1.def"))
                    includeDirs("/opt/homebrew/opt/secp256k1/include")
                    linkerOpts("-L/opt/homebrew/opt/secp256k1/lib", "-lsecp256k1")
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
        compileSdk = 36
        minSdk = 21
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

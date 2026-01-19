import com.android.build.api.dsl.androidLibrary

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    applyDefaultHierarchyTemplate()
    jvm()
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

    (iosTargets + listOf(macosX64, macosArm64)).forEach { target ->
        target.compilations.getByName("main") {
            cinterops {
                val libsodium by creating {
                    defFile(project.file("src/nativeInterop/cinterop/libsodium.def"))
                }
                val secp256k1 by creating {
                    defFile(project.file("src/nativeInterop/cinterop/secp256k1.def"))
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
                implementation(libs.kotlinx.datetime)

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
        val nativeMain by getting {
            dependencies {
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.client.darwin)
            }
        }
    }
}

import com.android.build.api.dsl.androidLibrary

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    applyDefaultHierarchyTemplate()
    jvm()
    androidLibrary {
        compileSdk = libs.versions.compileSdk.get().toInt()
        namespace = "com.bitchat.local"
        minSdk = libs.versions.minSdk.get().toInt()
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            binaryOption("bundleId", "local")
            isStatic = true
        }
    }
    val macosX64 = macosX64()
    val macosArm64 = macosArm64()
    listOf(macosX64, macosArm64).forEach { target ->
        target.binaries {
            sharedLib {
                baseName = "bitchat_location"
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":domain"))
                implementation(project(":data:cache"))
                implementation(project(":data:crypto"))
                implementation(project(":data:remote:transport"))
                implementation(project(":data:remote:transport:nostr"))

                implementation(libs.koin.core)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization)
                implementation(libs.kotlinx.datetime)
                // implementation("app.softwork:kotlinx-uuid-core:0.0.21")

                implementation(libs.kotlinx.atomicfu)
                implementation(libs.multiplatform.settings)
                implementation(libs.multiplatform.settings.serialization)
                implementation(libs.multiplatform.settings.observable)
                implementation(libs.multiplatform.settings.coroutines)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.mockk.common)
                implementation(libs.turbine)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.koin.android)
                implementation("androidx.security:security-crypto:1.1.0")
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(libs.credential.storage.jvm)
                implementation(libs.jna)
            }
        }
        val iosMain by getting {
        }
        val macosMain by getting {
        }
//        val iosTest by getting
    }
}

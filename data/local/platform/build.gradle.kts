import com.android.build.api.dsl.androidLibrary

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

    if (embeddedEnabled) {
        // Linux ARM64 target
        linuxArm64()
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

        // Apple-specific (iOS + macOS) - uses KeychainSettings
        val appleMain by getting {
        }

        if (embeddedEnabled) {
            // Linux-specific - uses file-based settings
            val linuxMain by getting {
            }
        }
//        val iosTest by getting
    }
}

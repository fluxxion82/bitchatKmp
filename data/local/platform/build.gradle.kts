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
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            binaryOption("bundleId", "local")
            isStatic = true
        }
    }
    val macosArm64 = macosArm64()
    listOf(macosArm64).forEach { target ->
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
        val jvmTest by getting {
            dependencies {
                implementation(libs.mockk.common)
                implementation(libs.turbine)
                implementation(libs.kotlin.test)
                implementation(libs.kotlin.test.junit)
                implementation(libs.kotlinx.coroutines.test)
                // The secure-storage tests stub SecretStore directly.
                implementation(libs.credential.storage.jvm)
            }
        }
        val iosMain by getting {
        }
        val macosMain by getting {
        }

        // Apple-specific (iOS + macOS) - the Keychain-backed secure store
        val appleMain by getting {
        }
        // Runs on the host as :data:local:platform:macosArm64Test, against a fake Keychain.
        // :data:local:platform:iosSimulatorArm64Test runs it again in a simulator, with the check
        // that the iOS graph binds the store that stays readable on a locked phone.
        val appleTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        if (embeddedEnabled) {
            // Linux-specific - uses file-based settings
            val linuxMain by getting {
                dependencies {
                    implementation(project(":data:local:statedir"))
                }
            }
        }
//        val iosTest by getting
    }
}

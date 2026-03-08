import com.android.build.api.dsl.androidLibrary

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

val embeddedEnabled = providers.gradleProperty("embedded.enabled")
    .map(String::toBoolean)
    .orElse(false)
    .get()

kotlin {
    applyDefaultHierarchyTemplate()

    androidLibrary {
        compileSdk = libs.versions.compileSdk.get().toInt()
        namespace = "com.bitchat.lora.meshcore"
        minSdk = libs.versions.minSdk.get().toInt()
    }

    jvm()

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            binaryOption("bundleId", "meshcore")
            isStatic = true
        }
    }

    macosX64()
    macosArm64()
    if (embeddedEnabled) {
        linuxArm64()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":data:remote:transport:lora"))
                implementation(project(":data:remote:transport"))

                implementation(libs.koin.core)
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        val androidMain by getting {
            dependencies {
                implementation(libs.koin.android)
            }
        }

        val jvmMain by getting {
            dependencies {
                // For TCP socket connection to meshcore-pi
            }
        }

        if (embeddedEnabled) {
            val linuxArm64Main by getting {
                dependencies {
                    // POSIX sockets for TCP connection to meshcore-pi
                }
            }
        }
    }
}

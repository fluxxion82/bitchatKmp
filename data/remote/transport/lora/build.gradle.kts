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

    androidLibrary {
        compileSdk = libs.versions.compileSdk.get().toInt()
        namespace = "com.bitchat.lora"
        minSdk = libs.versions.minSdk.get().toInt()
    }

    jvm()

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            binaryOption("bundleId", "lora")
            isStatic = true
        }
    }

    macosX64()
    macosArm64()

    if (embeddedEnabled) {
        // Linux ARM64 target for Raspberry Pi with SPI LoRa modules
        linuxArm64 {
            compilations.getByName("main") {
                cinterops {
                    val spi by creating {
                        defFile(project.file("src/nativeInterop/cinterop/spi.def"))
                    }
                }
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":data:remote:transport"))
                implementation(project(":data:cache"))

                implementation(libs.koin.core)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization)
                implementation(libs.kotlinx.datetime)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlin.test.common)
                implementation(libs.kotlin.test.annotations.common)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
            }
        }

        val androidMain by getting {
            dependencies {
                implementation(libs.koin.android)
                implementation(libs.usb.serial.android)
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(libs.jserialcomm)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(libs.kotlin.test.junit)
                implementation(libs.mockk)
                implementation(libs.mockk.agent.jvm)
                implementation(libs.assertj.core)
            }
        }
    }
}

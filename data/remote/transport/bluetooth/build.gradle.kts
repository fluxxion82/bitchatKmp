plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

version = "0.0.1"

kotlin {
    applyDefaultHierarchyTemplate()

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            binaryOption("bundleId", "bluetooth")
            transitiveExport = true

            baseName = "bluetooth"
        }
    }
    val macosX64 = macosX64()
    val macosArm64 = macosArm64()
    listOf(macosX64, macosArm64).forEach { target ->
        target.binaries {
            sharedLib {
                baseName = "bitchat_ble"
            }
        }
    }

    jvm("desktop")
    androidTarget()
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":domain"))
                implementation(project(":data:remote:rest:dto"))
                implementation(project(":data:cache"))
                implementation(project(":data:crypto"))
                implementation(project(":data:noise"))
                implementation(project(":data:local:platform"))

                implementation(libs.koin.core)
                implementation(libs.kotlinx.serialization)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
            }
        }
        val commonTest by getting {
            dependencies { }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.koin.android)
                // implementation(libs.bouncy.castle.bcprov)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.jna)
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlin.test.junit)
                implementation(libs.kotlinx.coroutines.test)

                implementation(libs.bcprov)

                implementation(libs.mockk.common)
                implementation(libs.mockk)
                implementation(libs.mockk.agent.jvm)
                implementation(libs.mockito.kotlin)
                implementation(libs.mockito.core)
                implementation(libs.junit)

                implementation(libs.assertj.core)
            }
        }

        val iosMain by getting
        val iosTest by getting

        val appleMain by getting
        val desktopMacMain by creating {
            dependsOn(appleMain)
        }
        val macosX64Main by getting {
            dependsOn(desktopMacMain)
        }
        val macosArm64Main by getting {
            dependsOn(desktopMacMain)
        }
    }
}

android {
    compileSdk = libs.versions.compileSdk.get().toInt()
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    namespace = "com.bitchat.ble"
}

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
}

version = "0.0.1"
val embeddedEnabled = providers.gradleProperty("embedded.enabled")
    .map(String::toBoolean)
    .orElse(false)
    .get()

kotlin {
    applyDefaultHierarchyTemplate()
    jvm("desktop")
    androidLibrary {
        namespace = "com.bitchat.mediautils"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
    }
    if (embeddedEnabled) {
        linuxArm64()
    }
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            binaryOption("bundleId", "mediautils")
            isStatic = true
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(compose.runtime)
                // LogPolicy / logPath: media paths stay out of logs unless opted in
                implementation(project(":domain"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test.common)
                implementation(libs.kotlin.test.annotations.common)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.exifinterface)
                implementation(libs.androidx.annotation)
                implementation(compose.ui)
                api("com.mrljdx:ffmpeg-kit-full:6.0")
            }
        }
        val desktopMain by getting {
            dependencies {
                // FFmpeg for decoding M4A/AAC audio files on Desktop
                implementation("org.bytedeco:ffmpeg-platform:6.1.1-1.5.10")
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.kotlin.test.junit)
            }
        }
        val iosMain by getting {
            dependencies {

            }
        }
        if (embeddedEnabled) {
            val linuxArm64Main by getting {
                dependencies {
                    implementation(project(":data:local:statedir"))
                }
            }
        }
    }
}

tasks.register("testClasses")

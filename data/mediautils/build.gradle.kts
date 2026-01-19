plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
}

version = "0.0.1"

kotlin {
    applyDefaultHierarchyTemplate()
    jvm("desktop")
    androidTarget()
    listOf(
        iosX64(),
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
        val iosMain by getting {
            dependencies {

            }
        }
    }
}

android {
    namespace = "com.bitchat.mediautils"
    compileSdk = 36
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    defaultConfig {
        minSdk = 26
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    sourceSets {
        named("main") {
            manifest.srcFile("src/androidMain/AndroidManifest.xml")
            res.srcDirs("src/androidMain/res", "src/commonMain/composeResources")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    debugImplementation(libs.androidx.ui.tooling)
}

tasks.register("testClasses")

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
            binaryOption("bundleId", "design")
            isStatic = true
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(path = ":domain"))
                api(project(":presentation:design:imagepicker"))
                implementation(project(":presentation:viewvo"))
                implementation(project(":data:mediautils"))
                implementation("io.github.kevinnzou:compose-webview-multiplatform:2.0.3")

                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.coroutines.core)

                val composeBom = project.dependencies.platform(libs.compose.bom)
                implementation(composeBom)
                implementation(compose.runtime)

                implementation(compose.material)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.animation)
                implementation(compose.components.uiToolingPreview)
                implementation(compose.components.resources)
                implementation(compose.materialIconsExtended)

                implementation(libs.coil)
                implementation(libs.coil.compose)
                implementation(libs.coil.network)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test.common)
                implementation(libs.kotlin.test.annotations.common)
                implementation(libs.kotlinx.coroutines.test)

                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlin.test.junit)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.foundation)
                implementation(compose.desktop.currentOs)
                implementation(compose.preview)
                implementation(compose.components.uiToolingPreview)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(compose.preview)
                implementation(compose.components.uiToolingPreview)
                implementation(libs.browser)

                implementation("io.coil-kt.coil3:coil-video:3.3.0")

                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.lifecycle.runtime.ktx)
                implementation("androidx.compose.foundation:foundation")
                implementation("androidx.compose.foundation:foundation-layout")
                implementation("androidx.compose.ui:ui")
                implementation("androidx.compose.material:material")

                implementation(libs.androidx.exifinterface)

                implementation(libs.accompanist.permissions)
                implementation(libs.camera.camera2)
                implementation(libs.camera.lifecycle)
                implementation(libs.camera.view)
            }
        }
        val iosMain by getting {
            dependencies {
                // implementation("io.coil-kt.coil3:coil-video:3.1.0-SNAPSHOT")
                // api(project(":presentation:design:videoplayer"))
            }
        }
        val nativeMain by getting
    }
}

android {
    namespace = "com.bitchat.design"
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
    sourceSets {
        named("main") {
            manifest.srcFile("src/androidMain/AndroidManifest.xml")
            res.srcDirs(
                "src/androidMain/res",
                "src/commonMain/composeResources/drawable",
                "src/commonMain/composeResources/values",
                "src/commonMain/composeResources/font",
            )
            assets.srcDir("src/commonMain/composeResources/files")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}
dependencies {
    debugImplementation(libs.androidx.ui.tooling)
}

compose.resources {
    publicResClass = true
    generateResClass = always
}

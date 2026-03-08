plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
}

version = "0.0.1"
val embeddedEnabled = providers.gradleProperty("embedded.enabled")
    .map(String::toBoolean)
    .orElse(false)
    .get()
val embeddedComposeVersion = providers.gradleProperty("embedded.composeForkVersion")
    .orElse("9999.0.0-SNAPSHOT")
    .get()

kotlin {
    applyDefaultHierarchyTemplate()
    jvm("desktop")
    androidTarget()
    if (embeddedEnabled) {
        linuxArm64()
    }
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

                implementation(libs.kotlinx.coroutines.core)

                val composeBom = project.dependencies.platform(libs.compose.bom)
                implementation(composeBom)
                implementation(compose.runtime)

                implementation(compose.material)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.animation)
                // Note: compose.components.resources moved to platform source sets
                // because linuxArm64 needs explicit artifact (multiplatform module lacks this target)
                // Note: Coil is NOT in commonMain because linuxArm64 doesn't support it
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
                implementation(compose.components.resources)
                // Webview for desktop (map picker)
                implementation("io.github.kevinnzou:compose-webview-multiplatform:2.0.3")
                // Coil for desktop
                implementation(libs.coil)
                implementation(libs.coil.compose)
                implementation(libs.coil.network)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(compose.preview)
                implementation(compose.components.uiToolingPreview)
                implementation(compose.components.resources)
                implementation(libs.browser)
                // Webview for Android (map picker)
                implementation("io.github.kevinnzou:compose-webview-multiplatform:2.0.3")
                // Coil for Android
                implementation(libs.coil)
                implementation(libs.coil.compose)
                implementation(libs.coil.network)
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
                implementation(compose.components.resources)
                // Webview for iOS (map picker)
                implementation("io.github.kevinnzou:compose-webview-multiplatform:2.0.3")
                // Coil for iOS
                implementation(libs.coil)
                implementation(libs.coil.compose)
                implementation(libs.coil.network)
            }
        }
        val nativeMain by getting
        if (embeddedEnabled) {
            val linuxArm64Main by getting {
                dependencies {
                    // Webview not supported on embedded Linux
                    // Coil not supported on linuxArm64 - using stub implementations
                    // Compose Resources - explicit artifact since multiplatform module lacks this target
                    implementation("org.jetbrains.compose.components:components-resources-linuxArm64:$embeddedComposeVersion")
                }
            }
        }
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

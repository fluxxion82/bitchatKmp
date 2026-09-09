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
val embeddedComposeVersion = providers.gradleProperty("embedded.composeForkVersion")
    .orElse("9999.0.0-SNAPSHOT")
    .get()

kotlin {
    applyDefaultHierarchyTemplate()
    androidLibrary {
        namespace = "com.bitchat.design.imagepicker"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
        // ImageViewerFileProvider reads R.xml.file_paths, and the multiplatform Android
        // plugin does not generate an R class unless resources are turned on.
        androidResources {
            enable = true
        }
    }
    jvm()
    if (embeddedEnabled) {
        linuxArm64()
    }
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            binaryOption("bundleId", "imagepicker")
            isStatic = true
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(path = ":domain"))
                api(project(path = ":data:mediautils"))

                val composeBom = project.dependencies.platform(libs.compose.bom)
                implementation(composeBom)
                implementation(compose.runtime)

                implementation(compose.material)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.animation)
                // Note: compose.components.resources moved to platform source sets
                // because linuxArm64 needs explicit artifact (multiplatform module lacks this target)
                implementation(libs.kotlinx.coroutines.core)
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
                implementation(compose.components.resources)
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.lifecycle.runtime.ktx)
                implementation("androidx.compose.foundation:foundation")
                implementation("androidx.compose.foundation:foundation-layout")
                implementation("androidx.compose.ui:ui")
                implementation("androidx.compose.material:material")

                implementation(libs.androidx.exifinterface)
            }
        }
        val iosMain by getting {
            dependencies {
                implementation(compose.components.resources)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(compose.components.resources)
            }
        }
        if (embeddedEnabled) {
            val linuxArm64Main by getting {
                dependencies {
                    // Compose Resources - explicit artifact since multiplatform module lacks this target
                    implementation("org.jetbrains.compose.components:components-resources-linuxArm64:$embeddedComposeVersion")
                }
            }
        }
    }
}


compose.resources {
    publicResClass = true
    generateResClass = always
}

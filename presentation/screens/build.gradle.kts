import com.android.build.api.dsl.androidLibrary

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
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

if (embeddedEnabled) {
    configurations.matching { it.name.contains("linuxArm64", ignoreCase = true) }.configureEach {
        resolutionStrategy.eachDependency {
            val composeGroups = listOf(
                "org.jetbrains.compose.ui",
                "org.jetbrains.compose.foundation",
                "org.jetbrains.compose.material",
                "org.jetbrains.compose.material3",
                "org.jetbrains.compose.animation",
                "org.jetbrains.compose.runtime"
            )
            if (requested.group in composeGroups) {
                useVersion(embeddedComposeVersion)
                because("Using forked Compose with linuxArm64 support")
            }
            if (requested.group == "org.jetbrains.compose.components" &&
                requested.name.startsWith("components-resources")) {
                useVersion(embeddedComposeVersion)
                because("Using forked Compose components-resources with linuxArm64 support")
            }
        }
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xwhen-guards")
        freeCompilerArgs.add("-Xnon-local-break-continue")
        freeCompilerArgs.add("-Xmulti-dollar-interpolation")
        freeCompilerArgs.add("-Xreturn-value-checker=check")
    }

    applyDefaultHierarchyTemplate()
    jvm("desktop")
    androidLibrary {
        namespace = "com.bitchat.screens"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
    }
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            binaryOption("bundleId", "screens")
            isStatic = true
        }
    }

    if (embeddedEnabled) {
        // Linux ARM64 target for embedded devices
        linuxArm64()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(path = ":domain"))
                implementation(project(":presentation:design"))
                implementation(project(":presentation:viewmodel"))
                implementation(project(":presentation:viewvo"))

                implementation(libs.navigation.compose)
                implementation(libs.navigation.material.compose)

                implementation(libs.kotlinx.io.core)
                implementation(libs.kotlinx.serialization)

                val composeBom = project.dependencies.platform(libs.compose.bom)
                implementation(composeBom)
                implementation(compose.runtime)

                implementation(compose.material)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.animation)

                implementation(libs.koin.core)
                implementation(libs.koin.compose)
                implementation(libs.koin.composeVM)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test.common)
                implementation(libs.kotlin.test.annotations.common)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.preview)
                implementation(compose.foundation)
                implementation(compose.desktop.currentOs)
                implementation(compose.components.resources)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(compose.preview)
                implementation(libs.accompanist.permissions)
                implementation(libs.androidx.activity.compose)
                implementation(compose.components.resources)
            }
        }
        val iosMain by getting {
            dependencies {
                implementation(compose.components.resources)
            }
        }
        if (embeddedEnabled) {
            val linuxArm64Main by getting {
                dependencies {
                    // Explicit linuxArm64 artifacts from forked Compose
                    implementation("org.jetbrains.compose.components:components-resources-linuxArm64:$embeddedComposeVersion")
                }
            }
        }
    }
}

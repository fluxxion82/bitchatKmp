import com.android.build.api.dsl.androidLibrary

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
}

version = "0.0.1"

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
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization)
                implementation(libs.ktor.http)

                val composeBom = project.dependencies.platform(libs.compose.bom)
                implementation(composeBom)
                implementation(compose.runtime)

                implementation(compose.material)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
                implementation(compose.animation)
                implementation(compose.components.uiToolingPreview)
                implementation(compose.components.resources)

                implementation(libs.koin.core)
                implementation(libs.koin.compose)
                implementation(libs.koin.composeVM)

                implementation(libs.coil)
                implementation(libs.coil.compose)
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
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(compose.preview)
                implementation(libs.accompanist.permissions)
                implementation(libs.androidx.activity.compose)
            }
        }
    }
}

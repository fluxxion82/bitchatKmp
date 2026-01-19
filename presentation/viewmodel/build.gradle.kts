import com.android.build.api.dsl.androidLibrary

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

version = "0.0.1"

kotlin {
    applyDefaultHierarchyTemplate()
    androidLibrary {
        namespace = "com.bitchat.viewmodel"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
    }

    jvm()
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            binaryOption("bundleId", "viewmodel")
            isStatic = true
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(compose.runtime)
                implementation(libs.kotlinx.datetime)
                implementation(project(":domain"))
                implementation(project(":data:mediautils"))
//                implementation(project(":logging:logger"))
                implementation(project(":presentation:viewvo"))
                implementation(libs.koin.core)
                implementation(libs.koin.compose)
                implementation(libs.koin.composeVM)
                implementation(libs.lifecycle.viewmodel)
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
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.androidx.lifecycle.viewmodel.ktx)
            }
        }
        // val androidTest by getting
        val jvmMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlin.test.junit)

                implementation(libs.turbine)
                implementation(libs.mockk)
                implementation(libs.mockk.agent.jvm)
                implementation(libs.mockito.kotlin)
                implementation(libs.mockito.core)
                implementation(libs.junit)

                implementation(libs.assertj.core)
            }
        }
    }
}

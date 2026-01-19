import com.android.build.api.dsl.androidLibrary

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    applyDefaultHierarchyTemplate()
    jvm("desktop")
    androidLibrary {
        compileSdk = libs.versions.compileSdk.get().toInt()
        namespace = "com.bitchat.repo"
        minSdk = libs.versions.minSdk.get().toInt()
        withHostTestBuilder {}.configure {}
    }
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            binaryOption("bundleId", "remote")
            isStatic = true
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":domain"))
                implementation(project(":data:remote:transport:bluetooth"))
                implementation(project(":data:remote:rest:client"))
                implementation(project(":data:remote:rest:dto"))
                implementation(project(":data:remote:tor"))
                implementation(project(":data:cache"))
                implementation(project(":data:crypto"))
                implementation(project(":data:noise"))
                implementation(project(":data:local:platform"))
                implementation(project(":data:remote:transport:nostr"))
                implementation(project(":data:mediautils"))

                implementation(libs.koin.core)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.atomicfu)
                // implementation("app.softwork:kotlinx-uuid-core:0.0.21")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlin.test.common)
                implementation(libs.kotlin.test.annotations.common)
                implementation(libs.kotlinx.coroutines.test)

                implementation(libs.mockk.common)
                implementation(libs.turbine)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.ktor.client.cio)
                implementation(libs.koin.android)
            }
        }
        getByName("androidHostTest") {
            dependencies {
                implementation(libs.kotlin.test.junit)

                implementation(libs.mockk)
            }
        }
        val desktopMain by getting {
            dependencies {

            }
        }
        val iosMain by getting {
            dependencies {
            }
        }
    }
}

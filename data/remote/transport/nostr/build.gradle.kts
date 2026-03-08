import com.android.build.api.dsl.androidLibrary

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

val embeddedEnabled = providers.gradleProperty("embedded.enabled")
    .map(String::toBoolean)
    .orElse(false)
    .get()

kotlin {
    applyDefaultHierarchyTemplate()
    androidLibrary {
        compileSdk = libs.versions.compileSdk.get().toInt()
        namespace = "com.bitchat.nostr"
        minSdk = libs.versions.minSdk.get().toInt()
    }
    jvm()
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            binaryOption("bundleId", "nostr")
            isStatic = true
        }
    }
    macosX64()
    macosArm64()

    if (embeddedEnabled) {
        // Linux ARM64 target
        linuxArm64()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":data:remote:rest:client"))
                implementation(project(":data:remote:rest:dto"))
                implementation(project(":data:cache"))
                implementation(project(":data:crypto"))
                implementation(project(":data:noise"))
                implementation(project(":data:remote:transport"))

                implementation(libs.koin.core)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization)

                implementation(libs.ktor.content.negotiation)
                implementation(libs.ktor.client.logging)
                implementation(libs.ktor.kotlinx.serialization)
                implementation(libs.ktor.client.json)
                implementation(libs.ktor.client.serialization)

                implementation(libs.kotlinx.atomicfu)
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
                implementation(libs.koin.android)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.client.logging.jvm)
                implementation(libs.ktor.server.cio.jvm)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-test")
                implementation("org.jetbrains.kotlin:kotlin-test-junit")

            }
        }
        // Apple-specific (iOS + macOS) - uses Darwin engine and Foundation APIs
        val appleMain by getting {
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }

        if (embeddedEnabled) {
            // Linux-specific - uses Curl engine (CIO doesn't support TLS on Native)
            val linuxMain by getting {
                dependencies {
                    implementation(libs.ktor.client.curl)
                }
            }
        }
    }
}

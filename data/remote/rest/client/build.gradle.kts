plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    applyDefaultHierarchyTemplate()

    jvm()
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            binaryOption("bundleId", "apiclient")
            isStatic = true
        }
    }
    macosX64()
    macosArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":domain"))
                implementation(project(":data:remote:rest:dto"))
                implementation(project(":data:remote:tor"))

                implementation(libs.koin.core)
                implementation(libs.kotlinx.coroutines.core)

                implementation(libs.kotlinx.serialization)
                implementation(libs.kotlinx.datetime)

                implementation(libs.ktor.content.negotiation)
                implementation(libs.ktor.client.logging)
                implementation(libs.ktor.kotlinx.serialization)
                implementation(libs.ktor.client.json)
                implementation(libs.ktor.client.serialization)
                implementation(libs.ktor.client.websocket)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation("io.mockk:mockk-common:1.12.5")
                implementation("app.cash.turbine:turbine:1.1.0")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.ktor.client.cio)  // CIO engine
                implementation(libs.ktor.client.okhttp)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-test")
                implementation("org.jetbrains.kotlin:kotlin-test-junit")

            }
        }
        val nativeMain by getting {
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
    }
}

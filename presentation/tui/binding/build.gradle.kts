plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
}

version = "0.0.1"

val embeddedEnabled = providers.gradleProperty("embedded.enabled")
    .map(String::toBoolean)
    .orElse(false)
    .get()

kotlin {
    applyDefaultHierarchyTemplate()
    jvm()
    if (embeddedEnabled) {
        linuxArm64()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":domain"))
                implementation(project(":presentation:tui"))
                implementation(project(":presentation:viewmodel"))
                implementation(project(":presentation:viewvo"))
                implementation(libs.mosaic.runtime)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
                implementation(libs.koin.core)
                implementation(libs.lifecycle.viewmodel)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlin.test.junit)
            }
        }
    }
}

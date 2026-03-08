plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

val embeddedEnabled = providers.gradleProperty("embedded.enabled")
    .map(String::toBoolean)
    .orElse(false)
    .get()

kotlin {
    applyDefaultHierarchyTemplate()
    jvm()
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            isStatic = true
        }
    }
    macosX64()
    macosArm64()
    if (embeddedEnabled) {
        linuxArm64()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":domain"))

                implementation(libs.kotlinx.serialization)
                // implementation("app.softwork:kotlinx-uuid-core:0.0.26")
            }
        }

        // Apple-specific (uses platform.zlib)
        val appleMain by getting {
        }

        if (embeddedEnabled) {
            // Linux-specific (stub implementation - no zlib cinterop)
            val linuxMain by getting {
            }
        }
    }
}

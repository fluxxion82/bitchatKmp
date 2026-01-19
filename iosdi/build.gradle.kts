plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
}

version = "1.0.0"

kotlin {
    applyDefaultHierarchyTemplate()

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            baseName = "BitchatApp"
            isStatic = true
        }
    }
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.koin.core)
                implementation(libs.koin.compose)

                api(project(":domain"))

                api(project(":data:local:platform"))
                implementation(project(":data:noise"))
                implementation(project(":data:remote:transport"))
                implementation(project(":data:remote:transport:bluetooth"))
                implementation(project(":data:remote:transport:nostr"))
                implementation(project(":data:remote:rest:client"))
                implementation(project(":data:remote:tor"))
                api(project(":data:repo"))
                implementation(project(":presentation:design"))
                implementation(project(":presentation:screens"))
                implementation(project(":presentation:viewmodel"))
                implementation(project(":presentation:viewvo"))
            }
        }

        val iosMain by getting {}
    }
}

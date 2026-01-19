plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

version = "0.0.1"

kotlin {
    applyDefaultHierarchyTemplate()
    jvm()
    macosX64()
    macosArm64()
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            binaryOption("bundleId", "domain")
            isStatic = true
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xwhen-guards")
        freeCompilerArgs.add("-Xnon-local-break-continue")
        freeCompilerArgs.add("-Xmulti-dollar-interpolation")
        freeCompilerArgs.add("-Xreturn-value-checker=check")

        extraWarnings.set(true)
    }

    sourceSets.all {
        languageSettings.optIn("kotlin.time.ExperimentalTime")
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.koin.core)
                implementation(libs.kotlinx.serialization)
                implementation(libs.kotlinx.coroutines.core)

                implementation(libs.kotlinx.datetime)
            }
        }
        val commonTest by getting
        val jvmMain by getting {
            dependencies {
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-test-junit")

                implementation("io.mockk:mockk-agent-jvm:1.13.11")
                implementation("io.mockk:mockk:1.13.7")
                implementation("com.nhaarman.mockitokotlin2:mockito-kotlin:2.2.0")
                implementation("org.mockito:mockito-core:5.5.0")
                implementation("junit:junit:4.13.2")

                implementation("org.assertj:assertj-core:3.21.0")

                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
            }
        }

        val iosMain by getting
        val iosTest by getting
    }
}

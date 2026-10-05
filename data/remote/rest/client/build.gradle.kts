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
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            binaryOption("bundleId", "apiclient")
            isStatic = true
        }
    }
    macosArm64()

    if (embeddedEnabled) {
        // Linux ARM64 target
        linuxArm64()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":domain"))
                implementation(project(":data:remote:rest:dto"))
                implementation(project(":data:remote:tor"))

                implementation(libs.koin.core)
                implementation(libs.kotlinx.coroutines.core)

                implementation(libs.kotlinx.serialization)

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

        // Apple-specific (iOS + macOS) - uses Darwin engine
        val appleMain by getting {
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
        // Darwin SOCKS capture harness (macosArm64Test, iosSimulatorArm64Test); see the A1 tests.
        val appleTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
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

// Opt-in public-name leak probe (DarwinPublicLeakProbeTest): -Pbitchat.publicLeakProbe=true. Default
// runs and verify.sh are unaffected; the probe cases then print one "disabled" line each and return.
val publicLeakProbe = providers.gradleProperty("bitchat.publicLeakProbe")
    .map(String::toBoolean)
    .orElse(false)
    .get()

// RouteAwareSocksCaptureTest asserts that a routed client still reaches the SOCKS proxy while the
// environment tells every HTTP library to bypass proxies, so the whole suite runs with that hostile
// environment set. Without it the test fails on a plain checkout rather than proving anything.
tasks.matching { it.name == "jvmTest" }.configureEach {
    (this as? org.gradle.api.tasks.testing.Test)?.apply {
        environment("NO_PROXY", "*")
        environment("no_proxy", "*")
    }
}

// Both native suites bind the leak detector's UDP port on the host loopback, and the configuration
// cache lets tasks of one project run in parallel: keep the simulator suite after the macOS one.
tasks.matching { it.name == "iosSimulatorArm64Test" }.configureEach {
    mustRunAfter(tasks.matching { it.name == "macosArm64Test" })
}

// The Darwin capture harness reports its leak-detector state on stdout; the gate has to show it.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    if (publicLeakProbe) {
        environment("BITCHAT_PUBLIC_LEAK_PROBE", "true")
        // `simctl spawn` forwards only SIMCTL_CHILD_-prefixed variables to the simulator process.
        if (this is org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest) {
            environment("SIMCTL_CHILD_BITCHAT_PUBLIC_LEAK_PROBE", "true")
        }
        // A probe run is an observation for a packet capture: never satisfied by an earlier result.
        doNotTrackState("public leak probe always runs")
    }
}

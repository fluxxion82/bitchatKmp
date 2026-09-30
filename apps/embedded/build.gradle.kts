plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.jetbrains.compose)
}

val composeVersion = providers.gradleProperty("embedded.composeForkVersion")
    .orElse("9999.0.0-SNAPSHOT")
    .get()
val skikoVersion = providers.gradleProperty("embedded.skikoVersion")
    .orElse("0.9.47")
    .get()
val koinVersion = providers.gradleProperty("embedded.koinForkVersion")
    .orElse("4.2.2")
    .get()

// Pin the linuxArm64 Skiko artifact and the forked Compose for linuxArm64.
// This handles transitive dependencies from presentation modules.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.skiko" && requested.name == "skiko") {
            // Kotlin/Native cannot resolve the multiplatform metadata module for this target,
            // so redirect to the published platform artifact.
            useTarget("org.jetbrains.skiko:skiko-linuxarm64:$skikoVersion")
            because("Kotlin/Native needs the explicit linuxarm64 Skiko artifact")
        }
        // Force forked Compose artifacts for linuxArm64 support
        // Exclude components group - it's published per-platform, not as multiplatform module
        val composeGroups = listOf(
            "org.jetbrains.compose.ui",
            "org.jetbrains.compose.foundation",
            "org.jetbrains.compose.material",
            "org.jetbrains.compose.material3",
            "org.jetbrains.compose.animation",
            "org.jetbrains.compose.runtime"
        )
        if (requested.group in composeGroups) {
            useVersion(composeVersion)
            because("Using forked Compose with linuxArm64 support")
        }
        // For components-resources, force all artifacts to SNAPSHOT (has linuxArm64)
        if (requested.group == "org.jetbrains.compose.components" &&
            requested.name.startsWith("components-resources")) {
            useVersion(composeVersion)
            because("Using forked Compose components-resources with linuxArm64 support")
        }
    }
}

kotlin {
    linuxArm64 {
        binaries {
            executable {
                entryPoint = "com.bitchat.embedded.main"
                baseName = "bitchat-embedded"
                // Link against DRM, GBM, EGL, GLESv2
                val sysrootLib = project.file("sysroot/usr/lib/aarch64-linux-gnu").absolutePath
                // Bluetooth module library paths (linkerOpts in .def propagate -l flags,
                // but -L paths must be on the binary since .def can't use relative paths)
                val btGattlibLib = project.file("../../data/remote/transport/bluetooth/native/gattlib/build/linux-arm64/install/lib").absolutePath
                val btSysrootLib = project.file("../../data/remote/transport/bluetooth/native/sysroot/lib/aarch64-linux-gnu").absolutePath
                linkerOpts(
                    "-L$sysrootLib",
                    "-L$btGattlibLib",
                    "-L$btSysrootLib",
                    "-ldrm", "-lgbm", "-lEGL", "-lGLESv2",
                    // Skia/Skiko font dependencies
                    "-lfontconfig", "-lfreetype",
                    "-lpng16", "-lz", "-lexpat", "-lbz2",
                    // Nothing to do with Skiko, whatever the old comment here said: skiko
                    // linuxarm64 ships static archives inside the klib, and this flag only relaxes
                    // symbol resolution for shared objects on the link line. What it covers is
                    // glibc version skew between the extracted Pi sysroot and the older glibc
                    // Kotlin/Native links linux_arm64 against. Dropping it fails with exactly
                    // three errors, measured:
                    //   stat64@GLIBC_2.33, fstat64@GLIBC_2.33  <- sysroot libdrm.so
                    //   pow@GLIBC_2.29                         <- sysroot libpng16.so
                    // All three resolve fine on the device (Debian bookworm, glibc 2.36), so this
                    // is a build-host artefact, not a missing symbol. libgbm.so.1 and
                    // libEGL_mesa.so.0 never even reach the check: LLD skips a DSO whose own
                    // DT_NEEDED entries are not all on the link line, and the sysroot has no
                    // libwayland-*, libxcb-* or libexpat.so.1. Keep the flag; the experiment has
                    // been run twice now.
                    "--allow-shlib-undefined",
                )
            }
        }
        val sysrootInclude = project.file("sysroot/usr/include")

        compilations.getByName("main") {
            cinterops {
                val drm by creating {
                    defFile(project.file("src/nativeInterop/cinterop/drm.def"))
                    includeDirs(sysrootInclude, project.file("sysroot/usr/include/libdrm"))
                }
                val gbm by creating {
                    defFile(project.file("src/nativeInterop/cinterop/gbm.def"))
                    includeDirs(sysrootInclude)
                }
                val egl by creating {
                    defFile(project.file("src/nativeInterop/cinterop/egl.def"))
                    includeDirs(sysrootInclude)
                    compilerOpts("-DMESA_EGL_NO_X11_HEADERS")
                }
                val gles2 by creating {
                    defFile(project.file("src/nativeInterop/cinterop/gles2.def"))
                    includeDirs(sysrootInclude)
                }
                val evdev by creating {
                    defFile(project.file("src/nativeInterop/cinterop/evdev.def"))
                    includeDirs(sysrootInclude)
                }
                val i2c by creating {
                    defFile(project.file("src/nativeInterop/cinterop/i2c.def"))
                    includeDirs(sysrootInclude)
                }
                val select by creating {
                    defFile(project.file("src/nativeInterop/cinterop/select.def"))
                    includeDirs(sysrootInclude)
                }
            }
        }
    }

    sourceSets {
        val linuxArm64Main by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)

                // Upstream Skiko from Maven Central. Since 0.9.47 the linuxarm64 artifact
                // bundles an EGL-only Skia (no GLX object at all), which is why the local
                // EGL fork was dropped -- see docs/FORKED_LIBRARIES.md.
                implementation("org.jetbrains.skiko:skiko-linuxarm64:$skikoVersion")

                implementation("org.jetbrains.compose.runtime:runtime:$composeVersion")
                implementation("org.jetbrains.compose.foundation:foundation-linuxarm64:$composeVersion")
                implementation("org.jetbrains.compose.material3:material3-linuxarm64:$composeVersion")
                implementation("org.jetbrains.compose.ui:ui-linuxarm64:$composeVersion")

                implementation("org.jetbrains.compose.foundation:foundation-layout-linuxarm64:$composeVersion")
                implementation("org.jetbrains.compose.animation:animation-linuxarm64:$composeVersion")
                implementation("org.jetbrains.compose.animation:animation-core-linuxarm64:$composeVersion")
                implementation("org.jetbrains.compose.ui:ui-geometry-linuxarm64:$composeVersion")
                implementation("org.jetbrains.compose.ui:ui-graphics-linuxarm64:$composeVersion")
                implementation("org.jetbrains.compose.ui:ui-text-linuxarm64:$composeVersion")
                implementation("org.jetbrains.compose.ui:ui-unit-linuxarm64:$composeVersion")
                implementation("org.jetbrains.compose.ui:ui-util-linuxarm64:$composeVersion")

                // Koin - explicit linuxarm64 artifacts to bypass multiplatform module resolution
                implementation("io.insert-koin:koin-core-linuxarm64:$koinVersion")
                implementation("io.insert-koin:koin-compose-linuxarm64:$koinVersion")
                implementation("io.insert-koin:koin-compose-viewmodel-linuxarm64:$koinVersion")

                // Lifecycle - explicit linuxarm64 artifacts to bypass multiplatform module resolution
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-common-linuxarm64:$composeVersion")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-linuxarm64:$composeVersion")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose-linuxarm64:$composeVersion")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-linuxarm64:$composeVersion")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-savedstate-linuxarm64:$composeVersion")

                implementation("org.jetbrains.androidx.savedstate:savedstate-linuxarm64:$composeVersion")

                // Build identity, build-config module, user-state initializer, LoRa protocol selector
                implementation(project(":apps:embedded-common"))

                // Domain for BitchatMessage model
                implementation(project(":domain"))
                implementation(project(":presentation:design"))
                implementation(project(":presentation:viewmodel"))
                implementation(project(":presentation:viewvo"))
                implementation(project(":presentation:screens"))

                // Data layer modules for real data access
                implementation(project(":data:crypto"))
                implementation(project(":data:local:platform"))
                implementation(project(":data:remote:rest:client"))
                implementation(project(":data:repo"))
                implementation(project(":data:cache"))
                implementation(project(":data:remote:transport:nostr"))
                implementation(project(":data:remote:transport:bluetooth"))
                implementation(project(":data:remote:transport:lora"))
                implementation(project(":data:remote:transport:lora:bitchat"))
                implementation(project(":data:remote:transport:lora:meshtastic"))
                implementation(project(":data:remote:transport:lora:meshcore"))
                implementation(project(":data:noise"))
                implementation(project(":data:remote:tor"))
            }
        }
    }
}

// Build identity: generated in :apps:embedded-common (one git identity for every embedded binary);
// the link sidecar is written by the shared script below, named after this executable.
extra["embeddedBinaryBaseName"] = "bitchat-embedded"
apply(from = rootProject.file("gradle/embedded-link-sidecar.gradle.kts"))

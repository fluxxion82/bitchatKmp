plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.jetbrains.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.cocoapods) apply false
}

val embeddedEnabled = providers.gradleProperty("embedded.enabled")
    .map(String::toBoolean)
    .orElse(false)
    .get()
val embeddedComposeVersion = providers.gradleProperty("embedded.composeForkVersion")
    .orElse("1.12.1-embedded-SNAPSHOT")
    .get()
val embeddedMaterial3Version = providers.gradleProperty("embedded.material3ForkVersion")
    .orElse("1.12.1-embedded-SNAPSHOT")
    .get()
val embeddedLifecycleVersion = providers.gradleProperty("embedded.lifecycleForkVersion")
    .orElse("2.11.0-embedded-SNAPSHOT")
    .get()
val embeddedSavedstateVersion = providers.gradleProperty("embedded.savedstateForkVersion")
    .orElse("1.5.0-alpha01-embedded-SNAPSHOT")
    .get()
val embeddedNavigationVersion = providers.gradleProperty("embedded.navigationForkVersion")
    .orElse("2.10.0-alpha05-embedded-SNAPSHOT")
    .get()
val embeddedNavigationEventVersion = providers.gradleProperty("embedded.navigationEventForkVersion")
    .orElse("1.1.1-embedded-SNAPSHOT")
    .get()
val embeddedKoinVersion = providers.gradleProperty("embedded.koinForkVersion")
    .orElse("4.2.2-embedded-SNAPSHOT")
    .get()
// KT-88544 workaround for release Apple binaries, see apple.kotlinNativeReleaseArgs in gradle.properties.
val appleKotlinNativeReleaseArgs = providers.gradleProperty("apple.kotlinNativeReleaseArgs")
    .orElse("")
    .get()
    .split(' ')
    .filter(String::isNotBlank)

subprojects {
    configurations.all {
        if (embeddedEnabled) {
            resolutionStrategy.eachDependency {
                val forkedComposeModules = setOf(
                    "animation", "animation-core", "animation-core-desktop", "animation-core-linuxarm64",
                    "animation-desktop", "animation-linuxarm64", "foundation", "foundation-desktop",
                    "foundation-layout", "foundation-layout-desktop", "foundation-layout-linuxarm64",
                    "foundation-linuxarm64", "material", "material-desktop", "material-linuxarm64",
                    "material-navigation", "material-navigation-desktop", "material-navigation-linuxarm64",
                    "material-ripple", "material-ripple-desktop", "material-ripple-linuxarm64", "runtime",
                    "runtime-desktop", "runtime-linuxarm64", "runtime-saveable", "runtime-saveable-desktop",
                    "runtime-saveable-linuxarm64", "ui", "ui-backhandler", "ui-backhandler-desktop",
                    "ui-backhandler-linuxarm64", "ui-desktop", "ui-geometry", "ui-geometry-desktop",
                    "ui-geometry-linuxarm64", "ui-graphics", "ui-graphics-desktop", "ui-graphics-linuxarm64",
                    "ui-linuxarm64", "ui-text", "ui-text-desktop", "ui-text-linuxarm64", "ui-tooling-preview",
                    "ui-tooling-preview-desktop", "ui-tooling-preview-linuxarm64", "ui-unit",
                    "ui-unit-desktop", "ui-unit-linuxarm64", "ui-util", "ui-util-desktop", "ui-util-linuxarm64"
                )
                val forkedComponentsModules = setOf(
                    "components-resources", "components-resources-desktop", "components-resources-linuxArm64"
                )
                val forkedLifecycleModules = setOf(
                    "lifecycle-common", "lifecycle-common-jvm", "lifecycle-common-linuxarm64", "lifecycle-runtime",
                    "lifecycle-runtime-compose", "lifecycle-runtime-compose-desktop", "lifecycle-runtime-compose-linuxarm64",
                    "lifecycle-runtime-desktop", "lifecycle-runtime-linuxarm64", "lifecycle-viewmodel",
                    "lifecycle-viewmodel-compose", "lifecycle-viewmodel-compose-desktop", "lifecycle-viewmodel-compose-linuxarm64",
                    "lifecycle-viewmodel-desktop", "lifecycle-viewmodel-linuxarm64", "lifecycle-viewmodel-savedstate",
                    "lifecycle-viewmodel-savedstate-desktop", "lifecycle-viewmodel-savedstate-linuxarm64"
                )
                val forkedSavedstateModules = setOf(
                    "savedstate", "savedstate-compose", "savedstate-compose-desktop", "savedstate-compose-linuxarm64",
                    "savedstate-desktop", "savedstate-linuxarm64"
                )
                val forkedNavigationModules = setOf(
                    "navigation-common", "navigation-common-desktop", "navigation-common-linuxarm64", "navigation-compose",
                    "navigation-compose-desktop", "navigation-compose-linuxarm64", "navigation-runtime",
                    "navigation-runtime-desktop", "navigation-runtime-linuxarm64"
                )
                val forkedNavigationEventModules = setOf(
                    "navigationevent-compose", "navigationevent-compose-desktop", "navigationevent-compose-linuxarm64"
                )
                val forkedKoinModules = setOf(
                    "koin-core", "koin-core-jvm", "koin-core-linuxarm64", "koin-core-viewmodel",
                    "koin-core-viewmodel-jvm", "koin-core-viewmodel-linuxarm64", "koin-compose", "koin-compose-jvm",
                    "koin-compose-linuxarm64", "koin-compose-viewmodel", "koin-compose-viewmodel-jvm",
                    "koin-compose-viewmodel-linuxarm64"
                )
                when {
                    requested.group in setOf(
                        "org.jetbrains.compose.animation", "org.jetbrains.compose.foundation",
                        "org.jetbrains.compose.material", "org.jetbrains.compose.runtime", "org.jetbrains.compose.ui"
                    ) && requested.name in forkedComposeModules -> useVersion(embeddedComposeVersion)
                    requested.group == "org.jetbrains.compose.material3" && requested.name in setOf(
                        "material3", "material3-desktop", "material3-linuxarm64"
                    ) -> useVersion(embeddedMaterial3Version)
                    requested.group == "org.jetbrains.compose.components" && requested.name in forkedComponentsModules ->
                        useVersion(embeddedComposeVersion)
                    requested.group == "org.jetbrains.androidx.lifecycle" && requested.name in forkedLifecycleModules ->
                        useVersion(embeddedLifecycleVersion)
                    requested.group == "org.jetbrains.androidx.savedstate" && requested.name in forkedSavedstateModules ->
                        useVersion(embeddedSavedstateVersion)
                    requested.group == "org.jetbrains.androidx.navigation" && requested.name in forkedNavigationModules ->
                        useVersion(embeddedNavigationVersion)
                    requested.group == "org.jetbrains.androidx.navigationevent" && requested.name in forkedNavigationEventModules ->
                        useVersion(embeddedNavigationEventVersion)
                    requested.group == "io.insert-koin" && requested.name in forkedKoinModules -> {
                    useVersion(embeddedKoinVersion)
                    because("Using published forked Koin modules with linuxArm64 support")
                }
                }
            }
        }
    }

    // Add opt-in for ExperimentalTime API (for all Kotlin compilations including Native)
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
        compilerOptions {
            freeCompilerArgs.add("-opt-in=kotlin.time.ExperimentalTime")
        }
    }

    // Every release link for an Apple target: the iOS frameworks (Xcode's Release configuration, so an
    // archive, links them through :iosdi:embedAndSignAppleFrameworkForXcode), the macOS dylibs and the
    // release test binaries :apps:apple-canary runs. The backend passes run at link time, so this is
    // where -Xdisable-phases has to go. Debug links keep the default pipeline.
    // It goes through linkTaskProvider.configure, not tasks.withType<KotlinNativeLink>().configureEach:
    // configureEach runs before the Kotlin plugin's own registration action, which resets
    // toolOptions.freeCompilerArgs to the compilation's, so an arg added there never reaches konanc.
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
            targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
                if (konanTarget.family.isAppleFamily) {
                    binaries.matching { it.buildType == org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.RELEASE }
                        .configureEach {
                            linkTaskProvider.configure {
                                toolOptions.freeCompilerArgs.addAll(appleKotlinNativeReleaseArgs)
                            }
                        }
                }
            }
        }
    }

    // Pin JVM/Android bytecode to Java 17 regardless of the JDK running Gradle. KMP modules with no
    // Java sources skip the Kotlin Gradle plugin's JVM-target validation, so they silently tracked
    // the host JDK (class-file 65 on a JDK 21 host); single-platform kotlin-android/kotlin-jvm
    // modules always run the check. This task-level value overrides module-level compilerOptions.
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    // Plain kotlin("jvm") modules (apps/desktop) do run the validation, and their Java plugin
    // defaults compileJava to the host JDK, so pin Java to 17 there as well (AGP modules already
    // pin it through android.compileOptions).
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension>("java") {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
}

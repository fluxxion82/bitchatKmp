plugins {
    kotlin("jvm")
}

// The one version both desktop apps report. :apps:desktop:compose (its installers) and
// :apps:desktop:tui (--version and Settings) read it from here, so they cannot drift apart.
version = "1.0.0"

dependencies {
    implementation(project(":domain"))
    implementation(project(":data:local:platform"))
    implementation(project(":data:remote:rest:client"))
    implementation(project(":data:remote:transport:bluetooth"))
    implementation(project(":data:remote:transport:lora"))
    implementation(project(":data:remote:transport:lora:bitchat"))
    implementation(project(":data:remote:transport:lora:meshtastic"))
    implementation(project(":data:remote:transport:nostr"))
    implementation(project(":data:remote:tor"))
    implementation(project(":data:repo"))
    implementation(project(":presentation:viewmodel"))

    implementation(libs.koin.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.koin.test)
    // TorStartupGraphTest replaces the platform Settings.Factory with an in-memory one.
    testImplementation(libs.multiplatform.settings)
}

tasks.test {
    useJUnitPlatform()
}

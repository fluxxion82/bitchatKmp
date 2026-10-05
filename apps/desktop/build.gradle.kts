plugins {
    kotlin("jvm")
}

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

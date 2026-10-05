plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Embedded profile only. macosArm64 exists solely so :data:local:statedir:macosArm64Test can run
// this plain-POSIX code on the build host; a linuxArm64 test binary only runs on the device.
kotlin {
    linuxArm64()
    macosArm64()

    sourceSets {
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

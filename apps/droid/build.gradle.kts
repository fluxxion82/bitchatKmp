plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.bitchat.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.bitchat.android"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            // isProfileable = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data:remote:rest:client"))
    implementation(project(":data:remote:transport:bluetooth"))
    implementation(project(":data:local:platform"))
    implementation(project(":data:remote:transport:nostr"))
    implementation(project(":data:remote:tor"))
    implementation(project(":presentation:design"))
    implementation(project(":presentation:screens"))
    implementation(project(":presentation:viewmodel"))
    implementation(project(":presentation:viewvo"))
    implementation(project(":data:repo"))

    implementation(libs.koin.core)
    implementation(libs.koin.compose)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    val composeBom = project.dependencies.platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.ui:ui-util")
    implementation("androidx.compose.runtime:runtime-livedata")
    implementation("androidx.compose.material:material")

    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.work:work-runtime-ktx:2.11.0")

    implementation(libs.accompanist.permissions)

    implementation(libs.androidx.activity.compose)

    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation(libs.androidx.core.ktx)
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
}
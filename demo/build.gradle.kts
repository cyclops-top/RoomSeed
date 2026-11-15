plugins {
    alias(libs.plugins.android.application)
    id("top.cyclops.room-seed")
}

android {
    namespace = "top.cyclops.roomseed.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "top.cyclops.roomseed.demo"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":demo-dictionary"))
    implementation(project(":demo-library"))
    implementation(libs.coroutines.android)
}

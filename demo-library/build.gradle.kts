plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
    id("top.cyclops.room-seed")
}

android {
    namespace = "top.cyclops.roomseed.demo.room3.library"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

room3 {
    schemaDirectory("debug", layout.projectDirectory.dir("schemas/debug"))
    schemaDirectory("release", layout.projectDirectory.dir("schemas/release"))
}

dependencies {
    implementation(libs.room3.runtime)
    ksp(libs.room3.compiler)
    implementation(libs.sqlite.bundled)
}

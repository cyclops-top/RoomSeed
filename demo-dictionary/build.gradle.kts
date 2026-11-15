plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    id("top.cyclops.room-seed")
}

android {
    namespace = "top.cyclops.roomseed.demo.dictionary"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

room {
    schemaDirectory("debug", layout.projectDirectory.dir("schemas/debug"))
    schemaDirectory("release", layout.projectDirectory.dir("schemas/release"))
}

dependencies {
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
}

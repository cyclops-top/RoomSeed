plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = "top.cyclops"
version = "0.1.1"

kotlin {
    jvmToolchain(17)
}

dependencies {
    compileOnly(libs.agp.api)
    implementation(libs.gson)
    implementation(libs.snakeyaml)
    implementation(libs.sqlite.bundled.jvm)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}

tasks.withType<Jar>().configureEach {
    from(layout.projectDirectory.file("../LICENSE")) { into("META-INF") }
}

gradlePlugin {
    plugins {
        register("roomSeed") {
            id = "top.cyclops.room-seed"
            implementationClass = "top.cyclops.roomseed.gradle.RoomSeedPlugin"
            displayName = "Room Seed"
            description = "Prepares Room seed database inputs for Android application variants."
        }
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = false)
    signAllPublications()

    pom {
        val repositoryUrl = "https://github.com/cyclops-top/RoomSeed"
        name.set("Room Seed")
        description.set("Prepares Room seed database inputs for Android application variants.")
        url.set(repositoryUrl)
        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/license/mit")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("cyclops-top")
                name.set("Cyclops")
                url.set("https://github.com/cyclops-top")
            }
        }
        scm {
            url.set(repositoryUrl)
            connection.set("scm:git:$repositoryUrl.git")
            developerConnection.set("scm:git:ssh://git@github.com/cyclops-top/RoomSeed.git")
            tag.set("$version")
        }
    }
}

// Keep the native extension reproducible from the published sources artifact.
tasks.withType<Jar>().configureEach {
    if (name == "sourcesJar") from("src/main/native") { into("native") }
}

val nativeTargets = mapOf(
    "macos-aarch64" to "libroomseedicu.dylib",
    "macos-x86_64" to "libroomseedicu.dylib",
    "linux-aarch64" to "libroomseedicu.so",
    "linux-x86_64" to "libroomseedicu.so",
    "windows-x86_64" to "roomseedicu.dll",
)
val verifyNativeDistribution by tasks.registering {
    doLast {
        nativeTargets.forEach { (platform, library) ->
            check(file("src/main/resources/native/$platform/$library").isFile) {
                "Missing ICU binary for $platform. Download the tested Native ICU workflow artifacts before producing a multi-platform distribution."
            }
        }
    }
}
if (providers.gradleProperty("requireAllNativePlatforms").orNull == "true") {
    tasks.named("processResources") { dependsOn(verifyNativeDistribution) }
}

plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = "top.cyclops"
version = "0.1.0"

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
            tag.set("v$version")
        }
    }
}

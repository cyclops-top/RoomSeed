package top.cyclops.roomseed.gradle.task

import java.io.File
import org.gradle.api.GradleException

internal data class DependencySeed(val directory: File, val label: String) {
    val assets: File get() = directory.resolve("assets")
    val schema: File get() = directory.resolve("schema")

    fun databaseFile(): File =
        assets.walkTopDown().filter(File::isFile).singleOrNull()
            ?: throw GradleException("Room Seed: expected one database in ${assets.path}.")
}

internal fun dependencySeeds(bundles: Set<File>): Map<String, DependencySeed> {
    val databases = linkedMapOf<String, DependencySeed>()
    for (bundle in bundles.sorted()) {
        val seeds = bundle.listFiles()?.sorted()
            ?: throw GradleException("Room Seed: cannot read seed bundle ${bundle.path}.")
        for (directory in seeds) {
            val seed = DependencySeed(directory, directory.resolve("source.txt").readText())
            val previous = databases.putIfAbsent(directory.name, seed)
            if (previous != null) {
                throw GradleException(
                    "Room Seed: ${directory.name} has two dependency databases: ${previous.label} and ${seed.label}."
                )
            }
        }
    }
    return databases
}

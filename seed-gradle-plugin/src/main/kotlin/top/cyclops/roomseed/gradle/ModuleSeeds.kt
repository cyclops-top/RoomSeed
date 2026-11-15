package top.cyclops.roomseed.gradle

import com.android.build.api.variant.ComponentIdentity
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.Directory
import top.cyclops.roomseed.gradle.dsl.RoomSeedExtension
import top.cyclops.roomseed.seed.SeedDirectory
import top.cyclops.roomseed.seed.SeedInputException
import top.cyclops.roomseed.seed.SeedManifest

internal data class ModuleSeeds(
    val project: Project,
    val variant: String,
    val directories: List<SeedSource>,
)

internal data class SeedSource(
    val directory: Directory,
    val manifest: SeedManifest,
    val excluded: List<String>,
    val label: String,
)

internal fun discoverSeedSources(project: Project, variant: ComponentIdentity): List<SeedSource> {
    val configured = project.extensions.getByType(RoomSeedExtension::class.java).seedDir.orNull
    if (configured?.isBlank() == true) throw GradleException("Room Seed ${project.path}: seedDir must be non-empty.")
    val sourceSets = listOfNotNull(variant.name, variant.buildType, variant.flavorName) +
        variant.productFlavors.map { (_, flavor) -> flavor }
    val variantRoots = sourceSets.filter { it.isNotEmpty() && it != "main" }.map { "src/$it/seed" }
    val roots = variantRoots + (configured?.let(::listOf) ?: listOf("src/main/seed", "seed"))
    val selectedDatabases = mutableSetOf<String>()
    return roots.distinct().flatMap { root ->
        val groups = discoverSeedSources(project, project.layout.projectDirectory.dir(root))
            .groupBy { it.manifest.database }
        // Keep a database's ordered manifests together within the highest-priority source set.
        groups.filterKeys(selectedDatabases::add).values.flatten()
    }
}

private fun discoverSeedSources(project: Project, root: Directory): List<SeedSource> {
    val rootPath = root.asFile.toPath()
    if (!Files.exists(rootPath, NOFOLLOW_LINKS)) return emptyList()
    if (!Files.isDirectory(rootPath, NOFOLLOW_LINKS))
        throw GradleException("Room Seed ${project.path}: invalid seed directory $rootPath.")
    val manifests = project.fileTree(root.asFile) { include("**/${SeedManifest.FILE_NAME}") }.files
        .associateBy { it.parentFile }
    val directories = (manifests.keys + root.asFile).sorted()
    return try {
        directories.mapNotNull { parent ->
            val relative = root.asFile.toPath().relativize(parent.toPath())
            val directory = if (parent == root.asFile) root else root.dir(relative.toString())
            val manifestFile = manifests[parent]
            val excluded = directories.filter { it != parent && it.toPath().startsWith(parent.toPath()) }
                .map { parent.toPath().relativize(it.toPath()) }
            if (manifestFile == null) {
                val exclusions = excluded.map(parent.toPath()::resolve).toSet()
                val files = SeedDirectory.discover(parent.toPath(), excludedDirectories = exclusions)
                if (files.isNotEmpty()) {
                    throw SeedInputException("Missing ${SeedManifest.FILE_NAME} for seed files under $parent.")
                }
                return@mapNotNull null
            }
            val path = manifestFile.toPath()
            if (!Files.isRegularFile(path, NOFOLLOW_LINKS) ||
                path.toRealPath() != rootPath.toRealPath().resolve(rootPath.relativize(path))
            ) {
                throw SeedInputException("Seed manifest must be a regular file inside $rootPath: $path")
            }
            val contents = project.providers.fileContents(directory.file(SeedManifest.FILE_NAME)).asText.get()
            val manifest = SeedManifest.parse(contents, manifestFile.path)
            SeedSource(directory, manifest, excluded.map { it.joinToString(File.separator) }, manifestFile.path)
        }
    } catch (error: SeedInputException) {
        throw GradleException("Room Seed ${project.path}: ${error.message}", error)
    }
}

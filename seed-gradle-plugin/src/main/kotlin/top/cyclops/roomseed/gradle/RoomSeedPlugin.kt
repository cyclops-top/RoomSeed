package top.cyclops.roomseed.gradle

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import top.cyclops.roomseed.gradle.dsl.RoomSeedExtension
import top.cyclops.roomseed.gradle.task.BundleRoomSeedsTask
import top.cyclops.roomseed.gradle.task.BundledSeed
import top.cyclops.roomseed.gradle.task.GenerateRoomSeedTask
import top.cyclops.roomseed.gradle.task.MergeRoomSeedAssetsTask
import top.cyclops.roomseed.gradle.task.validateAssetPath

private const val SEED_ARTIFACT = "room-seed-bundle"

class RoomSeedPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.extensions.create("roomSeed", RoomSeedExtension::class.java)
        project.pluginManager.withPlugin("com.android.application") {
            project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
                .onVariants { variant -> registerApplicationSeeds(project, variant) }
        }
        project.pluginManager.withPlugin("com.android.library") {
            project.extensions.getByType(LibraryAndroidComponentsExtension::class.java)
                .onVariants { variant ->
                    val module = ModuleSeeds(project, variant.name, discoverSeedSources(project, variant))
                    if (module.directories.isNotEmpty()) {
                        project.afterEvaluate { publishLibrarySeeds(module) }
                    }
                }
        }
        project.afterEvaluate {
            if (!project.pluginManager.hasPlugin("com.android.application") &&
                !project.pluginManager.hasPlugin("com.android.library")
            ) {
                throw GradleException("Room Seed requires com.android.application or com.android.library in ${project.path}.")
            }
        }
    }

    private fun registerApplicationSeeds(project: Project, variant: ApplicationVariant) {
        val runtime = variant.runtimeConfiguration
        val buildPath = project.gradle.buildPath
        val directProjects = runtime.allDependencies.withType(ProjectDependency::class.java).map { it.path }.toSet()
        val local = ModuleSeeds(project, variant.name, discoverSeedSources(project, variant))
        if (local.directories.isEmpty() && directProjects.isEmpty()) return

        // Select the secondary artifact of the runtime variant AGP already chose.
        val bundles = runtime.incoming.artifactView {
            attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, SEED_ARTIFACT)
            componentFilter {
                it is ProjectComponentIdentifier && it.build.buildPath == buildPath && it.projectPath in directProjects
            }
            // Direct dependencies without seed bundles have no artifact of this type.
            isLenient = true
        }.files
        val suffix = variant.name.capitalized()
        val merge = project.tasks.register("generate${suffix}RoomSeedAssets", MergeRoomSeedAssetsTask::class.java) {
            group = "room seed"
            description = "Packages the Room seed databases for ${variant.name}."
        }
        val assets = variant.sources.assets
            ?: throw GradleException("Room Seed ${project.path} (${variant.name}) has no Android assets source.")
        assets.addGeneratedSourceDirectory(merge, MergeRoomSeedAssetsTask::outputDirectory)

        project.afterEvaluate {
            val databases = registerModuleSeeds(local, bundles)
            val published = project.tasks.register("publish${suffix}RoomSeed", MergeRoomSeedAssetsTask::class.java) {
                group = "room seed"
                description = "Publishes the Room seed databases for ${variant.name}."
                outputDirectory.set(project.layout.buildDirectory.dir("intermediates/room-seed/${variant.name}"))
                dependencyBundles.from(bundles)
                replacedDatabases.set(databases.keys)
                for (database in databases.values) addGeneratedDatabase(database)
            }
            merge.configure {
                val output = published.flatMap { it.outputDirectory }
                generatedDirectories.from(output)
                val label = "${project.path} (${variant.name})"
                sourceLabels.putAll(output.map { mapOf(it.asFile.absolutePath to label) })
            }
        }
    }

    private fun publishLibrarySeeds(module: ModuleSeeds) {
        val project = module.project
        val suffix = module.variant.capitalized()
        val databases = registerModuleSeeds(module).values
        val published = project.tasks.register("publish${suffix}RoomSeed", MergeRoomSeedAssetsTask::class.java) {
            group = "room seed"
            description = "Publishes the Room seed databases for ${module.variant}."
            outputDirectory.set(project.layout.buildDirectory.dir("intermediates/room-seed/${module.variant}"))
            for (database in databases) addGeneratedDatabase(database)
        }
        project.tasks.matching { it.name == "assemble$suffix" }.configureEach { dependsOn(published) }

        val entries = databases.map { database ->
            project.objects.newInstance(BundledSeed::class.java).apply {
                this.database.set(database.source.manifest.database)
                label.set(database.source.label)
                assets.set(database.output)
                schema.set(checkNotNull(database.schema))
            }
        }
        val bundle = project.tasks.register("bundle${suffix}RoomSeed", BundleRoomSeedsTask::class.java) {
            group = "room seed"
            description = "Provides database and schema artifacts for ${module.variant} consumers."
            seeds.set(entries)
            dependsOn(published)
            outputDirectory.set(project.layout.buildDirectory.dir("intermediates/room-seed-bundle/${module.variant}"))
        }
        project.configurations.named("${module.variant}RuntimeElements").configure {
            outgoing.variants.create("roomSeed") {
                attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, SEED_ARTIFACT)
                artifact(bundle.flatMap { it.outputDirectory }) {
                    type = SEED_ARTIFACT
                    builtBy(bundle)
                }
            }
        }
    }

    private fun MergeRoomSeedAssetsTask.addGeneratedDatabase(database: GeneratedSeed) {
        generatedDirectories.from(database.output)
        val label = database.source.label
        sourceLabels.putAll(database.output.map { mapOf(it.asFile.absolutePath to label) })
    }

    private fun registerModuleSeeds(
        module: ModuleSeeds,
        bundles: FileCollection? = null,
    ): Map<String, GeneratedSeed> {
        val project = module.project
        val databases = linkedMapOf<String, GeneratedSeed>()
        val hasRoom = project.pluginManager.hasPlugin("androidx.room") || project.pluginManager.hasPlugin("androidx.room3")
        module.directories.forEachIndexed { index, seed ->
            val database = seed.manifest.database
            val base = databases[database]
            val asset = seed.manifest.databaseFilePath
            val context = "Room Seed ${project.path} (${module.variant}) ${seed.label}"
            validateAssetPath(seed.label, asset)
            val wiring = if (base == null && (bundles == null || hasRoom)) {
                RoomKspWiring.resolve(project, module.variant, context)
            } else null
            val localSchema = base?.schema ?: wiring?.schemaExportDirectory?.dir(database)
            val task = project.tasks.register(
                "generate${module.variant.capitalized()}RoomSeedDatabase${index + 1}",
                GenerateRoomSeedTask::class.java,
            ) {
                group = "room seed"
                description = "Builds the Room seed database from ${seed.directory.asFile}."
                contextLabel.set(context)
                databaseName.set(database)
                databaseDirectory.set(localSchema)
                assetPath.set(asset)
                seedDirectory.set(seed.directory)
                orderedSeedPaths.set(seed.manifest.files)
                excludedSeedDirectories.set(seed.excluded)
                outputDirectory.set(project.layout.buildDirectory.dir(
                    "intermediates/room-seed-staging/${module.variant}/database${index + 1}",
                ))
                if (bundles != null) dependencyBundles.from(bundles)
                if (wiring != null) dependsOn(wiring.export, wiring.sync)
                if (base != null) {
                    val baseAsset = base.source.manifest.databaseFilePath
                    baseDatabase.set(base.output.map { it.file(baseAsset) })
                }
            }
            databases[database] = GeneratedSeed(seed, localSchema, task.flatMap { it.outputDirectory })
        }
        return databases
    }

    private data class GeneratedSeed(
        val source: SeedSource,
        val schema: Directory?,
        val output: Provider<Directory>,
    )

    private fun String.capitalized(): String = replaceFirstChar { it.uppercaseChar() }
}

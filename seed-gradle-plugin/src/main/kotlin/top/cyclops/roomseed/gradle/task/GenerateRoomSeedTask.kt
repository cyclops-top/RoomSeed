package top.cyclops.roomseed.gradle.task

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import top.cyclops.roomseed.database.SeedDatabaseBuilder
import top.cyclops.roomseed.schema.RoomSchemaFiles
import top.cyclops.roomseed.schema.RoomSchemaReader
import top.cyclops.roomseed.schema.SchemaFormatException
import top.cyclops.roomseed.seed.SeedDirectory
import top.cyclops.roomseed.seed.SeedFile
import top.cyclops.roomseed.seed.SeedInputException
import top.cyclops.roomseed.sqlite.SqliteException

@DisableCachingByDefault(because = "The database is built with a host-native SQLite library.")
internal abstract class GenerateRoomSeedTask : DefaultTask() {
    @get:Input abstract val contextLabel: Property<String>
    @get:Input abstract val databaseName: Property<String>
    @get:Input abstract val assetPath: Property<String>
    @get:Input abstract val orderedSeedPaths: ListProperty<String>
    @get:Input abstract val excludedSeedDirectories: ListProperty<String>
    @get:InputFiles @get:Optional @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val databaseDirectory: DirectoryProperty
    @get:Internal abstract val seedDirectory: DirectoryProperty
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE)
    abstract val baseDatabase: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dependencyBundles: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    // Individual files can have the same name in different directories. Keep their full paths
    // in the local fingerprint so moving them cannot silently change the import order.
    @get:InputFiles @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val seedInputs: List<File>
        get() = discoverSeeds().map { it.file.toFile() }

    init {
        outputs.upToDateWhen { (it as GenerateRoomSeedTask).canReuseOutput() }
    }

    @TaskAction
    fun generate() {
        val (schema, version) = selectSchema()
        val plan =
            try {
                RoomSchemaReader.read(schema, version)
            } catch (error: SchemaFormatException) {
                throw GradleException(
                    "${contextLabel.get()}: Invalid schema version $version at $schema: ${error.message}",
                    error,
                )
            }

        val directory = outputDirectory.get().asFile.toPath()
        val asset = assetPath.get()
        validateAssetPath(contextLabel.get(), asset)
        val output = directory.resolve(asset)
        try {
            validateOutputPath(directory, output)
            val seed = seedDirectory.get().asFile.toPath()
            val inputs = discoverSeeds()
            val base = baseFile()?.toPath()
            SeedDatabaseBuilder.build(plan, output, inputs, stagingParent = directory.parent, base = base)
            if (base != null) logger.lifecycle("Appended Room seed data from $seed to a copy of $base.")
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path ->
                    if (!output.startsWith(path)) Files.delete(path)
                }
            }
        } catch (error: SeedInputException) {
            throw generationFailure(schema, version, error)
        } catch (error: SqliteException) {
            throw generationFailure(schema, version, error)
        } catch (error: IOException) {
            throw generationFailure(schema, version, error)
        } catch (error: LinkageError) {
            throw generationFailure(schema, version, error)
        }
        logger.lifecycle(
            "${contextLabel.get()} validated schema ${plan.version} at $schema " +
                "(${plan.tables.size} tables, ${plan.ftsTables.size} FTS tables, " +
                "${plan.indices.size} indices, ${plan.views.size} views)."
        )
        logger.lifecycle("Generated Room database at $output (asset: $asset).")
    }

    private fun selectSchema() = try {
        val directory = dependencySeed()?.schema ?: databaseDirectory.orNull?.asFile
            ?: throw GradleException("${contextLabel.get()}: no Room schema producer for ${databaseName.get()}.")
        RoomSchemaFiles.selectLatest(directory.toPath())
    } catch (error: SchemaFormatException) {
        throw GradleException("${contextLabel.get()}: ${error.message}", error)
    }

    private fun dependencySeed() = dependencySeeds(dependencyBundles.files)[databaseName.get()]

    private fun baseFile() = baseDatabase.orNull?.asFile ?: dependencySeed()?.databaseFile()

    private fun discoverSeeds(): List<SeedFile> {
        val seed = seedDirectory.get().asFile.toPath()
        return try {
            SeedDirectory.discover(seed, orderedSeedPaths.get(), excludedSeedDirectories.get().map(seed::resolve).toSet())
        } catch (error: SeedInputException) {
            throw GradleException("${contextLabel.get()}: ${error.message}", error)
        }
    }

    private fun canReuseOutput(): Boolean {
        // SQL scripts may use time, randomness, or data outside the declared task inputs.
        if (discoverSeeds().any { it.format == "sql" }) return false
        selectSchema()
        val directory = outputDirectory.get().asFile.toPath()
        val asset = assetPath.get()
        validateAssetPath(contextLabel.get(), asset)
        val output = directory.resolve(asset)
        try {
            validateOutputPath(directory, output)
            SeedDatabaseBuilder.requireStandaloneFile(output)
            baseFile()?.toPath()?.let(SeedDatabaseBuilder::requireStandaloneFile)
            if (!Files.isRegularFile(output)) return false
            return Files.walk(directory).use { paths -> paths.allMatch(output::startsWith) }
        } catch (error: IOException) {
            throw GradleException("${contextLabel.get()}: ${error.message}", error)
        } catch (error: SqliteException) {
            throw GradleException("${contextLabel.get()}: ${error.message}", error)
        }
    }

    private fun validateOutputPath(directory: Path, output: Path) {
        var path = output
        while (path.startsWith(directory)) {
            if (Files.isSymbolicLink(path))
                throw IOException("Generated asset path contains a symbolic link: $path")
            path = path.parent
        }
    }

    private fun generationFailure(schema: Path, version: Int, error: Throwable) =
        GradleException(
            "${contextLabel.get()}: Cannot generate database from schema $version at $schema: ${error.message}",
            error,
        )
}

internal fun validateAssetPath(context: String, asset: String) {
    if (!asset.endsWith(".db") ||
        asset.split('/').any { it.isBlank() || it == "." || it == ".." } ||
        asset.any { it == '\\' || it == ':' || it.isISOControl() }
    ) {
        throw GradleException("$context requires a relative .db output path with forward slashes.")
    }
}

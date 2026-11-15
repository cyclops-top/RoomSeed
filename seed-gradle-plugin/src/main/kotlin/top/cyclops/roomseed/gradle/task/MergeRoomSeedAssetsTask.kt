package top.cyclops.roomseed.gradle.task

import java.nio.file.Files
import java.util.Locale
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

@DisableCachingByDefault(because = "Copies local generated database assets.")
internal abstract class MergeRoomSeedAssetsTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val generatedDirectories: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val dependencyBundles: ConfigurableFileCollection
    @get:Input abstract val replacedDatabases: SetProperty<String>
    @get:Input abstract val sourceLabels: MapProperty<String, String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:Inject abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun merge() {
        val assets = linkedMapOf<String, String>()
        val labels = sourceLabels.get()
        val dependencies = dependencySeeds(dependencyBundles.files)
            .filterKeys { it !in replacedDatabases.get() }
            .values.associate { it.assets to it.label }
        val sources = generatedDirectories.files.associateWith { labels.getValue(it.absolutePath) } + dependencies
        for ((directory, label) in sources.toSortedMap()) {
            Files.walk(directory.toPath()).use { paths ->
                paths.filter { Files.isRegularFile(it) }.forEach { file ->
                    val asset = directory.toPath().relativize(file).joinToString("/")
                    val key = asset.lowercase(Locale.ROOT)
                    val conflict = assets.keys.firstOrNull {
                        key == it || key.startsWith("$it/") || it.startsWith("$key/")
                    }
                    if (conflict != null) {
                        throw GradleException("Room Seed: asset '$asset' from $label conflicts with ${assets.getValue(conflict)}.")
                    }
                    assets[key] = label
                }
            }
        }
        fileSystem.sync {
            from(sources.keys)
            into(outputDirectory)
        }
    }
}

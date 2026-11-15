package top.cyclops.roomseed.gradle.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject
import top.cyclops.roomseed.schema.RoomSchemaFiles

internal abstract class BundledSeed {
    @get:Input abstract val database: Property<String>
    @get:Input abstract val label: Property<String>
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val assets: DirectoryProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val schema: DirectoryProperty
}

@DisableCachingByDefault(because = "Copies local generated databases and Room schemas.")
internal abstract class BundleRoomSeedsTask : DefaultTask() {
    @get:Nested abstract val seeds: ListProperty<BundledSeed>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:Inject abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun bundle() {
        val entries = seeds.get()
        fileSystem.sync {
            into(outputDirectory)
            for (seed in entries) {
                val database = seed.database.get()
                from(seed.assets) { into("$database/assets") }
                val schema = RoomSchemaFiles.selectLatest(seed.schema.get().asFile.toPath())
                from(schema.path.toFile()) { into("$database/schema") }
                val label = temporaryDir.resolve(database)
                label.writeText(seed.label.get())
                from(label) {
                    into(database)
                    rename { "source.txt" }
                }
            }
        }
    }
}

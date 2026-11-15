package top.cyclops.roomseed.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.TaskProvider

/** Task contract verified against the official Room 2.8.5 and Room 3.0.3 plugins. */
internal object RoomKspWiring {
    data class Tasks(
        val export: TaskProvider<Task>,
        val sync: TaskProvider<Task>,
        val schemaExportDirectory: Directory,
    )

    fun resolve(project: Project, variant: String, context: String): Tasks {
        fun fail(reason: String): Nothing =
            throw GradleException(
                "$context: $reason"
            )

        val room2 = project.pluginManager.hasPlugin("androidx.room")
        val room3 = project.pluginManager.hasPlugin("androidx.room3")
        if (room2 == room3 || !project.pluginManager.hasPlugin("com.google.devtools.ksp")) {
            fail("Automatic wiring requires one official Room plugin (androidx.room or androidx.room3) and KSP.")
        }

        val suffix = variant.replaceFirstChar { it.uppercaseChar() }
        val exportName = "ksp${suffix}Kotlin"
        val tasks = project.tasks
        if (exportName !in tasks.names) fail("Expected the KSP task $exportName for '$variant'.")
        val export = tasks.named(exportName)
        val exportTask = export.get()
        // A Room plugin applied only in a producer module may live in its own classloader.
        val roomPackage = if (room3) "androidx.room3" else "androidx.room"
        val (syncTask, copyType) =
            exportTask.finalizedBy.getDependencies(exportTask)
                .mapNotNull { task ->
                    generateSequence<Class<*>>(task.javaClass) { it.superclass }
                        .firstOrNull { it.name == "$roomPackage.gradle.RoomSchemaCopyTask" }
                        ?.let { task to it }
                }
                .singleOrNull()
                ?: fail(
                    "Expected one official Room schema copy task for $exportName. " +
                        "Check Room schemaDirectory for '$variant'."
                )
        val schemaExportDirectory =
            (copyType.getMethod("getSchemaDirectory").invoke(syncTask) as DirectoryProperty).get()
        val schemaOutputs =
            (copyType.getMethod("getApTaskSchemaOutputDirectories").invoke(syncTask) as ListProperty<*>).get()
        val stagedSchema =
            project.layout.buildDirectory.dir("intermediates/room/schemas/$exportName").get().asFile
        if (
            schemaOutputs.none { it is Directory && it.asFile == stagedSchema } ||
                stagedSchema !in exportTask.outputs.files.files
        ) {
            fail("${syncTask.name} does not consume the Room schema output of $exportName.")
        }
        return Tasks(export, tasks.named(syncTask.name), schemaExportDirectory)
    }
}

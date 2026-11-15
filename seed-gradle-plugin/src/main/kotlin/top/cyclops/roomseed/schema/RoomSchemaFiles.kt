package top.cyclops.roomseed.schema

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal data class SchemaFile(val path: Path, val version: Int)

internal object RoomSchemaFiles {
    private val versionFile = Regex("([0-9]+)\\.json")

    fun selectLatest(directory: Path): SchemaFile {
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS))
            fail("Missing Room database schema directory: $directory.")
        val selected =
            try {
                Files.newDirectoryStream(directory).use { files ->
                    files
                        .mapNotNull { file ->
                            val match =
                                versionFile.matchEntire(file.fileName.toString())
                                    ?: return@mapNotNull null
                            val number = match.groupValues[1]
                            val version = number.toIntOrNull()
                            if (
                                number.startsWith('0') ||
                                    version == null ||
                                    !Files.isRegularFile(file, NOFOLLOW_LINKS) ||
                                    !Files.isReadable(file)
                            ) {
                                fail("Invalid Room schema candidate: $file.")
                            }
                            SchemaFile(file, version)
                        }
                        .maxByOrNull { it.version }
                }
            } catch (error: IOException) {
                throw SchemaFormatException("Cannot read Room schema directory $directory.", error)
            }
        return selected ?: fail("No numbered Room schema JSON files in $directory.")
    }

    private fun fail(message: String): Nothing = throw SchemaFormatException(message)
}

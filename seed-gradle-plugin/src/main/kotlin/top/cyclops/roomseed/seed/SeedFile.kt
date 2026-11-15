package top.cyclops.roomseed.seed

import java.io.BufferedReader
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path

internal data class SeedFile(val relativePath: String, val file: Path, val format: String = "sql") {
    val tableName: String
        get() = file.fileName.toString().substringBeforeLast('.')

    fun readText(): String = withReader { it.readText() }

    fun <T> withReader(action: (BufferedReader) -> T): T =
        try {
            Files.newBufferedReader(file, Charsets.UTF_8).use { input ->
                input.mark(1)
                if (input.read() != '\uFEFF'.code) input.reset()
                action(input)
            }
        } catch (_: CharacterCodingException) {
            throw SeedInputException("Seed script '$relativePath' must be valid UTF-8")
        } catch (error: IOException) {
            throw SeedInputException("Cannot read seed script '$relativePath'", error)
        }
}

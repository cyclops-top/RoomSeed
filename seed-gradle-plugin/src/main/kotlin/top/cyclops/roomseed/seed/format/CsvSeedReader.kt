package top.cyclops.roomseed.seed.format

import java.io.PushbackReader
import top.cyclops.roomseed.seed.SeedFile
import top.cyclops.roomseed.seed.SeedInputException

internal object CsvSeedReader : SeedRowReader {
    override val extension = "csv"

    override fun read(file: SeedFile, emit: (SeedRow) -> Unit) {
        file.withReader { input ->
            val records = CsvRecords(PushbackReader(input), file.relativePath)
            val header =
                records.next()
                    ?: throw SeedInputException("${file.relativePath}: missing CSV header")
            if (header.any { it.isBlank() } || header.toSet().size != header.size) {
                throw SeedInputException(
                    "${file.relativePath}:1: CSV column names must be nonempty and unique"
                )
            }
            while (true) {
                val location = "${file.relativePath}:${records.line}"
                val fields = records.next() ?: break
                if (fields.size != header.size)
                    throw SeedInputException("$location: expected ${header.size} CSV fields")
                val values =
                    fields.map {
                        when (it) {
                            "\\N" -> SeedValue.Null
                            "\\\\N" -> SeedValue.RawText("\\N")
                            else -> SeedValue.RawText(it)
                        }
                    }
                emit(SeedRow(file.tableName, header.zip(values).toMap(), location))
            }
        }
    }
}

private class CsvRecords(private val input: PushbackReader, private val path: String) {
    var line = 1
        private set

    fun next(): List<String>? {
        var current = input.read()
        if (current == -1) return null
        val fields = mutableListOf<String>()
        val start = line
        while (true) {
            val field = StringBuilder()
            if (current == '"'.code) {
                current = input.read()
                while (true) {
                    if (current == -1)
                        throw SeedInputException("$path:$start: unterminated quoted CSV field")
                    if (current == '"'.code) {
                        current = input.read()
                        if (current != '"'.code) break
                    }
                    if (current == '\n'.code) line++
                    field.append(current.toChar())
                    current = input.read()
                }
            } else {
                while (
                    current != -1 &&
                        current != ','.code &&
                        current != '\r'.code &&
                        current != '\n'.code
                ) {
                    if (current == '"'.code)
                        throw SeedInputException("$path:$start: unexpected quote in CSV field")
                    field.append(current.toChar())
                    current = input.read()
                }
            }
            fields.add(field.toString())
            when (current) {
                -1 -> return fields
                ','.code -> current = input.read()
                '\r'.code,
                '\n'.code -> {
                    if (current == '\r'.code) {
                        val following = input.read()
                        if (following != -1 && following != '\n'.code) input.unread(following)
                    }
                    line++
                    return fields
                }
                else ->
                    throw SeedInputException(
                        "$path:$start: expected a delimiter after quoted CSV field"
                    )
            }
        }
    }
}

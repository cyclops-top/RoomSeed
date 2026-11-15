package top.cyclops.roomseed.seed.format

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.io.StringReader
import top.cyclops.roomseed.seed.SeedFile
import top.cyclops.roomseed.seed.SeedInputException

internal object JsonlSeedReader : SeedRowReader {
    override val extension = "jsonl"

    override fun read(file: SeedFile, emit: (SeedRow) -> Unit) {
        file.withReader { input ->
            input.lineSequence().forEachIndexed { index, line ->
                val location = "${file.relativePath}:${index + 1}"
                val cells =
                    try {
                        JsonReader(StringReader(line)).use { reader ->
                            reader.strictness = Strictness.STRICT
                            val row = readSeedObject(reader, location)
                            if (reader.peek() != JsonToken.END_DOCUMENT)
                                throw SeedInputException("$location: expected one JSON object per line")
                            row
                        }
                    } catch (error: IOException) {
                        throw SeedInputException("$location: invalid JSONL object", error)
                    } catch (error: IllegalStateException) {
                        throw SeedInputException(
                            "$location: expected one JSON object per line",
                            error,
                        )
                    }
                emit(SeedRow(file.tableName, cells, location))
            }
        }
    }
}

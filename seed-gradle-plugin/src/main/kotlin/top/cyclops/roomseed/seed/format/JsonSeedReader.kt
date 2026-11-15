package top.cyclops.roomseed.seed.format

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import top.cyclops.roomseed.seed.SeedFile
import top.cyclops.roomseed.seed.SeedInputException

internal object JsonSeedReader : SeedRowReader {
    override val extension = "json"

    override fun read(file: SeedFile, emit: (SeedRow) -> Unit) {
        file.withReader { input ->
            JsonReader(input).use { reader ->
                reader.strictness = Strictness.STRICT
                var location = file.relativePath
                try {
                    if (reader.peek() != JsonToken.BEGIN_ARRAY)
                        throw SeedInputException("$location: expected a JSON array of objects")
                    reader.beginArray()
                    var item = 1
                    while (true) {
                        location = "${file.relativePath}: item $item"
                        if (!reader.hasNext()) break
                        if (reader.peek() != JsonToken.BEGIN_OBJECT)
                            throw SeedInputException("$location: expected a JSON object")
                        emit(SeedRow(file.tableName, readSeedObject(reader, location), location))
                        item++
                    }
                    location = file.relativePath
                    reader.endArray()
                    if (reader.peek() != JsonToken.END_DOCUMENT)
                        throw SeedInputException("$location: expected one JSON array")
                } catch (error: IOException) {
                    throw SeedInputException("$location: invalid JSON array: ${error.message}", error)
                }
            }
        }
    }
}

internal fun readSeedObject(reader: JsonReader, location: String): Map<String, SeedValue> =
    buildMap {
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            if (key in this) throw SeedInputException("$location: duplicate column '$key'")
            put(
                key,
                when (reader.peek()) {
                    JsonToken.STRING -> SeedValue.Text(reader.nextString())
                    JsonToken.NUMBER -> SeedValue.Number(reader.nextString())
                    JsonToken.NULL -> {
                        reader.nextNull()
                        SeedValue.Null
                    }
                    else ->
                        throw SeedInputException(
                            "$location: column '$key' requires a string, number or null"
                        )
                },
            )
        }
        reader.endObject()
    }

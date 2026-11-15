package top.cyclops.roomseed.schema

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path

internal fun readSchemaJson(file: Path): SchemaJson {
    try {
        Files.newBufferedReader(file, Charsets.UTF_8).use { input ->
            JsonReader(input).use { reader ->
                reader.strictness = Strictness.STRICT
                val root = reader.readValue(0)
                if (reader.peek() != JsonToken.END_DOCUMENT)
                    root.fail("expected exactly one JSON document")
                return root
            }
        }
    } catch (error: CharacterCodingException) {
        throw SchemaFormatException("Schema must be valid UTF-8.", error)
    } catch (error: IOException) {
        throw SchemaFormatException("Cannot read schema JSON: ${error.message}", error)
    }
}

private fun JsonReader.readValue(depth: Int): SchemaJson {
    val location = path
    if (depth > 32)
        throw SchemaFormatException("$location: schema JSON exceeds 32 levels of nesting")
    val value =
        when (peek()) {
            JsonToken.BEGIN_OBJECT -> {
                beginObject()
                val fields = linkedMapOf<String, SchemaJson>()
                while (hasNext()) {
                    val name = nextName()
                    if (name in fields)
                        throw SchemaFormatException("$path: duplicate JSON key '$name'")
                    fields[name] = readValue(depth + 1)
                }
                endObject()
                fields
            }
            JsonToken.BEGIN_ARRAY -> {
                beginArray()
                val entries = buildList { while (hasNext()) add(readValue(depth + 1)) }
                endArray()
                entries
            }
            JsonToken.STRING -> nextString()
            JsonToken.NUMBER -> JsonNumber(nextString())
            JsonToken.BOOLEAN -> nextBoolean()
            JsonToken.NULL -> {
                nextNull()
                null
            }
            else -> throw SchemaFormatException("$location: expected a JSON value")
        }
    return SchemaJson(location, value)
}

private data class JsonNumber(val text: String)

internal class SchemaJson(private val path: String, private val value: Any?) {
    @Suppress("UNCHECKED_CAST")
    private fun fields(): Map<String, SchemaJson> =
        (value as? Map<String, SchemaJson>) ?: fail("expected an object")

    fun has(name: String): Boolean = name in fields()

    fun only(vararg allowed: String) {
        val unexpected = fields().keys - allowed.toSet()
        if (unexpected.isNotEmpty()) fail("unsupported fields: ${unexpected.joinToString()}")
    }

    fun field(name: String): SchemaJson = fields()[name] ?: SchemaJson("$path.$name", Missing)

    fun text(): String = (value as? String) ?: fail("expected a string")

    fun optionalText(): String? = if (value == Missing || value == null) null else text()

    fun integer(): Int =
        (value as? JsonNumber)?.text?.toIntOrNull() ?: fail("expected a 32-bit integer")

    fun boolean(default: Boolean? = null): Boolean =
        if (value == Missing && default != null) default
        else (value as? Boolean) ?: fail("expected a boolean")

    @Suppress("UNCHECKED_CAST")
    fun array(): List<SchemaJson> = (value as? List<SchemaJson>) ?: fail("expected an array")

    fun optionalArray(allowNull: Boolean = false): List<SchemaJson> =
        if (value == Missing || (allowNull && value == null)) emptyList() else array()

    fun fail(message: String): Nothing = throw SchemaFormatException("$path: $message")

    private object Missing
}

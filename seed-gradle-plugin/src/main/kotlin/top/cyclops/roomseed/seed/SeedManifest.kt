package top.cyclops.roomseed.seed

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException

internal data class SeedManifest(
    val database: String,
    val databaseFilePath: String,
    val files: List<String>,
) {
    companion object {
        const val FILE_NAME = "seek.yaml"

        fun parse(contents: String, source: String): SeedManifest {
            fun fail(message: String): Nothing = throw SeedInputException("$source: $message")
            val options = LoaderOptions().apply {
                isAllowDuplicateKeys = false
                maxAliasesForCollections = 0
            }
            val document = try {
                Yaml(SafeConstructor(options)).load<Any>(contents)
            } catch (error: YAMLException) {
                throw SeedInputException("$source: ${error.message}", error)
            }
            val mapping = document as? Map<*, *> ?: fail("expected a YAML mapping")
            val unknown = mapping.keys - setOf("database", "databaseFilePath", "files")
            if (unknown.isNotEmpty()) fail("unknown fields: ${unknown.joinToString()}")

            fun string(value: Any?, label: String): String =
                (value as? String)?.takeIf { it.isNotBlank() } ?: fail("$label must be a non-empty string")
            val database = string(mapping["database"], "database")
            if (!database.matches(Regex("[\\p{L}_$][\\p{L}\\p{N}_$]*(\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)*"))) {
                fail("database must be a Room database class name")
            }
            val databaseFilePath = string(mapping["databaseFilePath"], "databaseFilePath")
            val files = mapping["files"] as? List<*> ?: fail("files must be a list")
            if (files.isEmpty()) fail("files must contain at least one path or pattern")
            return SeedManifest(database, databaseFilePath, files.map { string(it, "each files entry") })
        }
    }
}

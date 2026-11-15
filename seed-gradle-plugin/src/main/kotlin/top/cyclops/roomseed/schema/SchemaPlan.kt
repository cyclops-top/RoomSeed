package top.cyclops.roomseed.schema

internal data class SchemaPlan(
    val version: Int,
    val identityHash: String,
    val tables: List<SchemaTable>,
    val views: List<SchemaView>,
    val ftsTables: List<SchemaFtsTable> = emptyList(),
) {
    val indices: List<SchemaIndex>
        get() = tables.flatMap { it.indices }
}

internal data class SchemaFtsTable(
    val name: String,
    val version: FtsVersion,
    val createSql: String,
    val columns: List<SchemaColumn>,
    val contentTable: String?,
    val triggers: List<SchemaTrigger>,
    val languageIdColumn: String?,
)

internal enum class FtsVersion(val shadowSuffixes: List<String>) {
    FTS4(listOf("content", "segments", "segdir", "docsize", "stat")),
    FTS5(listOf("data", "idx", "content", "docsize", "config")),
}

internal data class SchemaTrigger(val name: String, val createSql: String)

internal data class SchemaTable(
    val name: String,
    val createSql: String,
    val columns: List<SchemaColumn>,
    val primaryKey: SchemaPrimaryKey,
    val indices: List<SchemaIndex>,
    val foreignKeys: List<SchemaForeignKey>,
    val withoutRowId: Boolean,
)

internal data class SchemaColumn(
    val fieldPath: String,
    val name: String,
    val affinity: String,
    val notNull: Boolean,
    val defaultValue: String?,
)

internal data class SchemaPrimaryKey(val columnNames: List<String>, val autoGenerate: Boolean)

internal data class SchemaIndex(
    val name: String,
    val createSql: String,
    val unique: Boolean,
    val columnNames: List<String>,
    val orders: List<String>,
)

internal data class SchemaForeignKey(
    val table: String,
    val onDelete: String,
    val onUpdate: String,
    val columns: List<String>,
    val referencedColumns: List<String>,
)

internal data class SchemaView(val name: String, val createSql: String)

internal class SchemaFormatException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

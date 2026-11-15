package top.cyclops.roomseed.database

import java.util.Base64
import top.cyclops.roomseed.schema.SchemaColumn
import top.cyclops.roomseed.schema.SchemaPlan
import top.cyclops.roomseed.seed.SeedInputException
import top.cyclops.roomseed.seed.format.SeedRow
import top.cyclops.roomseed.seed.format.SeedValue
import top.cyclops.roomseed.sqlite.quoteIdentifier

internal class SeedRowImporter(
    plan: SchemaPlan,
    private val execute: (String, String, List<Any?>) -> Unit,
) {
    private val tables = plan.tables.associateBy { it.name }
    private val ftsTables = plan.ftsTables.associateBy { it.name }

    fun insert(row: SeedRow) {
        val table = tables[row.table]
        val fts = ftsTables[row.table]
        fts?.contentTable?.let { fail(row, "seed the ${fts.version} content table '$it' instead") }
        val schemaColumns = table?.columns ?: fts?.let {
            val languageId = it.languageIdColumn?.let { name ->
                SchemaColumn(name, name, "INTEGER", notNull = false, defaultValue = null)
            }
            it.columns + ftsRowId + listOfNotNull(languageId)
        }
            ?: fail(row, "unknown Room table '${row.table}'")
        val primaryKey = table?.primaryKey?.columnNames ?: listOf(ftsRowId.name)
        val autoGenerate = table?.primaryKey?.autoGenerate ?: true
        val unknown = row.cells.keys - schemaColumns.map { it.name }.toSet()
        if (unknown.isNotEmpty()) fail(row, "unknown columns: ${unknown.joinToString()}")
        val columns = schemaColumns.filter { it.name in row.cells }
        for (column in schemaColumns) {
            val generated = autoGenerate && column.name in primaryKey
            val required = column.notNull || column.name in primaryKey
            if (
                column.name !in row.cells && required && column.defaultValue == null && !generated
            ) {
                fail(row, "missing required column '${column.name}'")
            }
        }
        val arguments = columns.map { column ->
            bind(row, column, row.cells.getValue(column.name), column.name in primaryKey)
        }
        val tableName = quoteIdentifier(row.table)
        val sql =
            if (columns.isEmpty()) {
                "INSERT INTO $tableName DEFAULT VALUES"
            } else {
                val names = columns.joinToString(", ") { quoteIdentifier(it.name) }
                val parameters = columns.joinToString(", ") { "?" }
                "INSERT INTO $tableName ($names) VALUES ($parameters)"
            }
        execute(sql, row.location, arguments)
    }

    private fun bind(row: SeedRow, column: SchemaColumn, value: SeedValue, primaryKey: Boolean): Any? {
        if (value == SeedValue.Null) {
            if (column.notNull || primaryKey) {
                fail(row, "column '${column.name}' cannot be NULL")
            }
            return null
        }
        val text =
            when (value) {
                is SeedValue.Text -> value.value
                is SeedValue.Number -> value.value
                is SeedValue.RawText -> value.value
                SeedValue.Null -> error("NULL was already handled")
            }
        val converted =
            when (column.affinity) {
                "INTEGER" ->
                    if (value is SeedValue.Number || value is SeedValue.RawText) text.toLongOrNull()
                    else null
                "REAL" ->
                    if (
                        (value is SeedValue.Number || value is SeedValue.RawText) &&
                            decimal.matches(text)
                    ) {
                        text.toDoubleOrNull()?.takeIf { it.isFinite() }
                    } else null
                "TEXT" -> if (value is SeedValue.Text || value is SeedValue.RawText) text else null
                "BLOB" ->
                    if (
                        value is SeedValue.Text || value is SeedValue.RawText && text.isNotEmpty()
                    ) {
                        try {
                            Base64.getDecoder().decode(text)
                        } catch (_: IllegalArgumentException) {
                            null
                        }
                    } else null
                else -> null
            }
        return converted ?: fail(row, "column '${column.name}' requires ${column.affinity} data")
    }

    private fun fail(row: SeedRow, message: String): Nothing =
        throw SeedInputException("${row.location}: $message")

    private companion object {
        val ftsRowId = SchemaColumn("rowid", "rowid", "INTEGER", notNull = true, defaultValue = null)
        val decimal = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
    }
}

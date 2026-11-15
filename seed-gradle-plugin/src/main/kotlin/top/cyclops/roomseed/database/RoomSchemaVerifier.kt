package top.cyclops.roomseed.database

import top.cyclops.roomseed.schema.SchemaPlan
import top.cyclops.roomseed.schema.SchemaTable
import top.cyclops.roomseed.sqlite.SqliteDatabase

internal object RoomSchemaVerifier {
    fun verify(db: SqliteDatabase, plan: SchemaPlan, includesRoomMetadata: Boolean = false) {
        val expected = buildSet {
            if (includesRoomMetadata) add(listOf("table", "room_master_table", "room_master_table"))
            for (table in plan.tables) {
                add(listOf("table", table.name, table.name))
                for (index in table.indices) add(listOf("index", index.name, table.name))
            }
            for (view in plan.views) add(listOf("view", view.name, view.name))
            for (fts in plan.ftsTables) {
                add(listOf("table", fts.name, fts.name))
                for (trigger in fts.triggers) add(listOf("trigger", trigger.name, fts.contentTable))
            }
        }
        val shadowTables = db.query(
            "SELECT name FROM pragma_table_list WHERE [schema] = 'main' AND type = 'shadow'"
        ).map { it.single() }.toSet()
        val ftsShadowNames = plan.ftsTables.flatMap { fts ->
            fts.version.shadowSuffixes.map { "${fts.name}_$it" }
        }.toSet()
        val actual =
            db.query(
                "SELECT type, name, tbl_name FROM main.sqlite_schema WHERE name NOT GLOB 'sqlite_*'"
            ).filterNot { (type, name, _) -> type == "table" && name in shadowTables && name in ftsShadowNames }
        requireDatabase(actual.toSet() == expected, "SQLite objects differ from the Room schema")
        for (table in plan.tables) verifyTable(db, table)
        for (fts in plan.ftsTables) {
            val options = db.query(
                "SELECT type FROM pragma_table_list(?) WHERE [schema] = 'main'",
                "Checking ${fts.version} table '${fts.name}'",
                fts.name,
            )
            requireDatabase(options == listOf(listOf("virtual")), "'${fts.name}' is not a virtual table")
            val columns = db.query(
                "SELECT name, hidden FROM pragma_table_xinfo(?) ORDER BY cid",
                "Checking ${fts.version} columns of '${fts.name}'",
                fts.name,
            )
            requireDatabase(
                columns.filter { it[1] == "0" }.map { it[0] } ==
                    fts.columns.map { it.name } &&
                    (fts.languageIdColumn == null || listOf(fts.languageIdColumn, "1") in columns),
                "Columns of ${fts.version} table '${fts.name}' differ from the Room schema",
            )
        }
        for (view in plan.views) db.validateView(view.name)
    }

    private fun verifyTable(db: SqliteDatabase, table: SchemaTable) {
        val tableOptions =
            db.query(
                "SELECT wr FROM pragma_table_list(?) WHERE [schema] = 'main'",
                "Checking table options of '${table.name}'",
                table.name,
            )
        requireDatabase(
            tableOptions == listOf(listOf(if (table.withoutRowId) "1" else "0")),
            "Rowid mode of '${table.name}' differs from the Room schema",
        )
        val columns =
            db.query(
                "SELECT name, type, [notnull], dflt_value, pk, hidden FROM pragma_table_xinfo(?) ORDER BY cid",
                "Checking columns of '${table.name}'",
                table.name,
            )
        val expectedColumns =
            table.columns.map { column ->
                val keyPosition =
                    table.primaryKey.columnNames.indexOfFirst {
                        it.equals(column.name, ignoreCase = true)
                    } + 1
                listOf(
                    column.name,
                    column.affinity,
                    if (column.notNull) "1" else "0",
                    column.defaultValue,
                    "$keyPosition",
                    "0",
                )
            }
        val normalizedColumns = columns.map { it.normalizeDefault() }
        requireDatabase(
            normalizedColumns == expectedColumns.map { it.normalizeDefault() },
            "Columns of '${table.name}' differ from the Room schema",
        )
        val foreignKeyRows =
            db.query(
                "SELECT id, [table], [from], [to], on_delete, on_update FROM pragma_foreign_key_list(?) ORDER BY id, seq",
                "Checking foreign keys of '${table.name}'",
                table.name,
            )
        val foreignKeys =
            foreignKeyRows.groupBy { it.first() }.values.map { rows -> rows.map { it.drop(1) } }
        val expectedKeys =
            table.foreignKeys.map { key ->
                key.columns.zip(key.referencedColumns) { child, parent ->
                    listOf(key.table, child, parent, key.onDelete, key.onUpdate)
                }
            }
        requireDatabase(
            foreignKeys.groupingBy { it }.eachCount() == expectedKeys.groupingBy { it }.eachCount(),
            "Foreign keys of '${table.name}' differ from the Room schema",
        )
        for (index in table.indices) {
            val indexProperties =
                db.query(
                    "SELECT [unique], partial FROM pragma_index_list(?) WHERE name = ?",
                    "Checking index '${index.name}'",
                    table.name,
                    index.name,
                )
            requireDatabase(
                indexProperties == listOf(listOf(if (index.unique) "1" else "0", "0")),
                "Index properties of '${index.name}' differ from the Room schema",
            )
            val members =
                db.query(
                    "SELECT name, [desc] FROM pragma_index_xinfo(?) WHERE [key] = 1 ORDER BY seqno",
                    "Checking index columns of '${index.name}'",
                    index.name,
                )
            val expectedMembers =
                index.columnNames.zip(index.orders) { name, order ->
                    listOf(name, if (order == "DESC") "1" else "0")
                }
            requireDatabase(
                members == expectedMembers,
                "Columns of index '${index.name}' differ from the Room schema",
            )
        }
    }

    private fun List<String?>.normalizeDefault(): List<String?> = mapIndexed { index, value ->
        if (index == 3 && value != null) {
            var literal = value.trim()
            while (literal.startsWith('(') && literal.endsWith(')')) literal =
                literal.substring(1, literal.lastIndex).trim()
            literal
        } else value
    }
}

package top.cyclops.roomseed.schema

import java.nio.file.Path

internal object RoomSchemaReader {
    private const val TABLE = "\${TABLE_NAME}"
    private const val VIEW = "\${VIEW_NAME}"
    private const val MASTER_TABLE_DDL =
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)"
    private val hashPattern = Regex("[0-9A-Fa-f]{32}")
    private val virtualTable = Regex("^\\s*CREATE\\s+VIRTUAL\\s+TABLE\\b", RegexOption.IGNORE_CASE)
    private val placeholder = Regex("\\$\\{[^}]*}")
    private val affinities = setOf("INTEGER", "TEXT", "REAL", "BLOB")
    private val foreignKeyActions =
        setOf("NO ACTION", "RESTRICT", "SET NULL", "SET DEFAULT", "CASCADE")

    fun read(file: Path, fileVersion: Int): SchemaPlan {
        val root = readSchemaJson(file)
        val format = root.field("formatVersion")
        if (format.integer() != 1) format.fail("unsupported Room schema formatVersion; expected 1")
        root.only("formatVersion", "database")
        val database = root.field("database")
        database.only("version", "identityHash", "entities", "views", "setupQueries")
        val versionNode = database.field("version")
        val version = versionNode.integer()
        if (version <= 0) versionNode.fail("expected a positive database version")
        if (version != fileVersion)
            versionNode.fail("version $version does not match filename version $fileVersion")
        val hashNode = database.field("identityHash")
        val hash = hashNode.text()
        if (!hashPattern.matches(hash))
            hashNode.fail("expected a 32-digit hexadecimal identity hash")
        validateSetup(database.field("setupQueries"), hash)

        val entities = database.field("entities")
        val (ftsEntities, ordinaryEntities) = entities.array().partition { it.has("ftsVersion") }
        val tables = ordinaryEntities.map(::readTable)
        val ftsTables = ftsEntities.map { readFtsTable(it, tables) }
        val views = database.field("views").optionalArray().map(::readView)
        val indexNames = tables.flatMap { table -> table.indices.map { it.name } }
        requireDistinct(
            database,
            tables.map { it.name } + indexNames + views.map { it.name } +
                ftsTables.flatMap { listOf(it.name) + it.triggers.map { trigger -> trigger.name } },
        )
        val tableColumns =
            tables.associate { table ->
                table.name.sqliteName() to table.columns.map { it.name.sqliteName() }.toSet()
            }
        for (table in tables) {
            for (key in table.foreignKeys) {
                val parentColumns =
                    tableColumns[key.table.sqliteName()]
                        ?: entities.fail(
                            "table '${table.name}' references unknown table '${key.table}'"
                        )
                requireColumns(entities, key.referencedColumns, parentColumns)
            }
        }
        return SchemaPlan(version, hash, tables, views, ftsTables)
    }

    private fun validateSetup(node: SchemaJson, hash: String) {
        val expectedInsert =
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$hash')"
        if (node.array().map { it.text() } != listOf(MASTER_TABLE_DDL, expectedInsert)) {
            node.fail(
                "expected Room's two room_master_table setup queries with the exported identityHash"
            )
        }
    }

    private fun readTable(node: SchemaJson): SchemaTable {
        val sql = node.field("createSql").text()
        if (
            virtualTable.containsMatchIn(sql) ||
                listOf("ftsVersion", "ftsOptions", "contentSyncTriggers").any(node::has)
        ) {
            node.fail("unsupported virtual tables or FTS metadata without ftsVersion")
        }
        node.only(
            "tableName", "createSql", "fields", "primaryKey", "indices", "foreignKeys", "withoutRowId",
        )
        val name = node.field("tableName").identifier(reserveObjectNames = true)
        val fields = node.field("fields")
        val columns = fields.array().map(::readColumn)
        if (columns.isEmpty()) fields.fail("expected at least one column")
        requireDistinct(fields, columns.map { it.name })
        val columnNames = columns.map { it.name.sqliteName() }.toSet()
        val primaryKey = readPrimaryKey(node.field("primaryKey"), columns, columnNames)
        val withoutRowId = node.field("withoutRowId").boolean(false)
        if (withoutRowId && primaryKey.autoGenerate)
            node.field("withoutRowId").fail("WITHOUT ROWID requires a non-generated primary key")
        val indices = node.field("indices").optionalArray().map { readIndex(it, name, columnNames) }
        val foreignKeys =
            node.field("foreignKeys").optionalArray().map { readForeignKey(it, columnNames) }
        val createSql =
            resolveDdl(
                node.field("createSql"),
                "CREATE TABLE IF NOT EXISTS `$TABLE` (",
                TABLE,
                name,
            )
        return SchemaTable(name, createSql, columns, primaryKey, indices, foreignKeys, withoutRowId)
    }

    private fun readFtsTable(node: SchemaJson, tables: List<SchemaTable>): SchemaFtsTable {
        val versionNode = node.field("ftsVersion")
        val module = versionNode.text()
        val version = FtsVersion.entries.firstOrNull { it.name == module }
            ?: versionNode.fail("only Room FTS4 and FTS5 virtual tables are supported")
        node.only(
            "tableName", "createSql", "fields", "primaryKey", "indices", "foreignKeys",
            "ftsVersion", "ftsOptions", "contentSyncTriggers",
        )
        val name = node.field("tableName").identifier(reserveObjectNames = true)
        val options = node.field("ftsOptions")
        val languageIdColumn = if (version == FtsVersion.FTS4) {
            options.field("languageIdColumnName").optionalText()?.takeIf { it.isNotEmpty() }
        } else null
        val fields = node.field("fields")
        val columns = fields.array().map(::readColumn)
        requireDistinct(fields, columns.map { it.name })
        if (columns.isEmpty() || columns.any { it.affinity != "TEXT" })
            fields.fail("$version requires TEXT fields")
        val key = node.field("primaryKey")
        key.only("columnNames", "autoGenerate", "algorithm")
        val keys = key.field("columnNames").array().map { it.identifier().sqliteName() }
        // Room exports the rowid in primary-key metadata but omits it from the fields.
        if (keys.isNotEmpty() && keys != listOf("rowid"))
            key.fail("$version primary key must be the optional rowid")
        key.field("autoGenerate").boolean()
        for (property in listOf("indices", "foreignKeys")) {
            val value = node.field(property)
            if (value.optionalArray().isNotEmpty()) value.fail("$version does not declare $property")
        }
        val content = options.field("contentTable")
        val contentName = content.text().takeIf { it.isNotEmpty() }
        val triggers = node.field("contentSyncTriggers")
        val triggerSql = triggers.array().map { it.text() }
        val syncTriggers =
            if (contentName == null) {
                if (triggerSql.isNotEmpty()) triggers.fail("standalone $version cannot have content sync triggers")
                emptyList()
            } else {
                val table = tables.firstOrNull { it.name.sqliteName() == contentName.sqliteName() }
                    ?: content.fail("unknown external content table '$contentName'")
                if (version == FtsVersion.FTS4 && table.withoutRowId)
                    content.fail("FTS4 external content requires a rowid table")
                requireColumns(
                    fields,
                    columns.map { it.name } + listOfNotNull(languageIdColumn),
                    table.columns.map { it.name.sqliteName() }.toSet(),
                )
                readFtsTriggers(triggers, triggerSql, name, table.name)
            }
        val sql = resolveDdl(
            node.field("createSql"), "CREATE VIRTUAL TABLE IF NOT EXISTS `$TABLE` USING $version(", TABLE, name,
        )
        return SchemaFtsTable(name, version, sql, columns, contentName, syncTriggers, languageIdColumn)
    }

    private fun readFtsTriggers(
        node: SchemaJson,
        statements: List<String>,
        name: String,
        contentTable: String,
    ): List<SchemaTrigger> {
        val events = listOf("BEFORE UPDATE", "BEFORE DELETE", "AFTER UPDATE", "AFTER INSERT")
        if (statements.size != events.size) node.fail("expected Room's four FTS content sync triggers")
        return events.map { event ->
            val trigger = "room_fts_content_sync_${name}_${event.replace(' ', '_')}"
            val prefix = "CREATE TRIGGER IF NOT EXISTS $trigger $event ON `$contentTable` BEGIN "
            val statement = statements.singleOrNull { it.startsWith(prefix) && it.endsWith("; END") }
                ?: node.fail("expected Room's FTS content sync trigger '$trigger'")
            SchemaTrigger(trigger, statement)
        }
    }

    private fun readColumn(node: SchemaJson): SchemaColumn {
        node.only("fieldPath", "columnName", "affinity", "notNull", "defaultValue")
        val fieldPath = node.field("fieldPath").text()
        if (fieldPath.isBlank() || fieldPath.any { it.isISOControl() })
            node.field("fieldPath").fail("invalid field path")
        val name = node.field("columnName").identifier()
        val affinity = node.field("affinity").text()
        if (affinity !in affinities) node.field("affinity").fail("unsupported Room column affinity")
        val defaultValue = node.field("defaultValue").optionalText()
        if (defaultValue != null && !isLiteralDefault(defaultValue)) {
            node.field("defaultValue").fail("expected a deterministic SQL literal default")
        }
        return SchemaColumn(
            fieldPath,
            name,
            affinity,
            node.field("notNull").boolean(false),
            defaultValue,
        )
    }

    private fun readPrimaryKey(
        node: SchemaJson,
        columns: List<SchemaColumn>,
        names: Set<String>,
    ): SchemaPrimaryKey {
        node.only("autoGenerate", "columnNames", "algorithm")
        val keys = node.field("columnNames").columnNames(names)
        val autoGenerate = node.field("autoGenerate").boolean()
        if (node.has("algorithm")) {
            val algorithm = node.field("algorithm")
            if (algorithm.text() !in setOf("AUTOINCREMENT", "ROWID"))
                algorithm.fail("unsupported Room primary key algorithm")
        }
        if (keys.isEmpty()) node.fail("expected at least one primary key column")
        if (
            autoGenerate &&
                (keys.size != 1 ||
                    columns.none {
                        it.name.sqliteName() == keys.single().sqliteName() &&
                            it.affinity == "INTEGER"
                    })
        ) {
            node.fail("autoGenerate requires one INTEGER primary key column")
        }
        return SchemaPrimaryKey(keys, autoGenerate)
    }

    private fun readIndex(node: SchemaJson, table: String, names: Set<String>): SchemaIndex {
        node.only("name", "unique", "columnNames", "orders", "createSql")
        val name = node.field("name").identifier(reserveObjectNames = true)
        val unique = node.field("unique").boolean()
        val columns = node.field("columnNames").columnNames(names)
        val orders = node.field("orders").optionalArray(allowNull = true).map { it.text() }
        if (columns.isEmpty()) node.field("columnNames").fail("expected at least one index column")
        if (
            orders.isNotEmpty() &&
                (orders.size != columns.size || orders.any { it != "ASC" && it != "DESC" })
        ) {
            node.field("orders").fail("expected one ASC or DESC per index column")
        }
        val indexType = if (unique) "UNIQUE INDEX" else "INDEX"
        val sql =
            resolveDdl(
                node.field("createSql"),
                "CREATE $indexType IF NOT EXISTS `$name` ON `$TABLE` (",
                TABLE,
                table,
            )
        return SchemaIndex(
            name,
            sql,
            unique,
            columns,
            orders.ifEmpty { List(columns.size) { "ASC" } },
        )
    }

    private fun readForeignKey(node: SchemaJson, names: Set<String>): SchemaForeignKey {
        node.only("table", "onDelete", "onUpdate", "columns", "referencedColumns")
        val parent = node.field("table").identifier(reserveObjectNames = true)
        val onDelete = node.field("onDelete").foreignKeyAction()
        val onUpdate = node.field("onUpdate").foreignKeyAction()
        val columns = node.field("columns").columnNames(names)
        val referenced = node.field("referencedColumns").array().map { it.identifier() }
        if (columns.isEmpty() || columns.size != referenced.size)
            node.fail("expected matching non-empty foreign key columns")
        requireDistinct(node.field("referencedColumns"), referenced)
        return SchemaForeignKey(parent, onDelete, onUpdate, columns, referenced)
    }

    private fun readView(node: SchemaJson): SchemaView {
        node.only("viewName", "createSql")
        val name = node.field("viewName").identifier(reserveObjectNames = true)
        return SchemaView(
            name,
            resolveDdl(node.field("createSql"), "CREATE VIEW `$VIEW` AS ", VIEW, name),
        )
    }

    private fun resolveDdl(node: SchemaJson, prefix: String, token: String, name: String): String {
        val sql = node.text()
        if (!sql.startsWith(prefix) || sql.drop(prefix.length).isBlank() || '\u0000' in sql) {
            node.fail("expected Room's CREATE SQL for '$name'")
        }
        return sql.replace(token, name).also {
            if (placeholder.containsMatchIn(it)) node.fail("unresolved schema placeholder")
        }
    }

    private fun SchemaJson.identifier(reserveObjectNames: Boolean = false): String {
        val name = text()
        if (
            name.isBlank() ||
                name.any { it.isISOControl() || it == '`' } ||
                placeholder.containsMatchIn(name)
        ) {
            fail("invalid SQLite identifier")
        }
        if (
            reserveObjectNames &&
                (name.sqliteName().startsWith("sqlite_") ||
                    name.sqliteName() in setOf("room_master_table", "android_metadata"))
        ) {
            fail("reserved SQLite or Room object name '$name'")
        }
        return name
    }

    private fun SchemaJson.columnNames(available: Set<String>): List<String> =
        array()
            .map { it.identifier() }
            .also {
                requireDistinct(this, it)
                requireColumns(this, it, available)
            }

    private fun SchemaJson.foreignKeyAction(): String =
        text().also { if (it !in foreignKeyActions) fail("unsupported foreign key action") }

    private fun requireColumns(node: SchemaJson, names: List<String>, available: Set<String>) {
        val unknown = names.filter { it.sqliteName() !in available }
        if (unknown.isNotEmpty()) node.fail("unknown columns: ${unknown.joinToString()}")
    }

    private fun requireDistinct(node: SchemaJson, names: List<String>) {
        val seen = mutableSetOf<String>()
        for (name in names) if (!seen.add(name.sqliteName()))
            node.fail("duplicate SQLite name '$name'")
    }

    private fun String.sqliteName(): String =
        map { if (it in 'A'..'Z') it.lowercaseChar() else it }.joinToString("")

    private val literalDefault =
        Regex(
            "(?:NULL|TRUE|FALSE|[+-]?(?:0x[0-9a-f]+|(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:e[+-]?[0-9]+)?)|'(?:[^'\\u0000]|'')*+'|x'(?:[0-9a-f]{2})*+')",
            RegexOption.IGNORE_CASE,
        )

    private fun isLiteralDefault(value: String): Boolean {
        var literal = value.trim()
        while (literal.startsWith('(') && literal.endsWith(')')) literal =
            literal.substring(1, literal.lastIndex).trim()
        return literalDefault.matches(literal)
    }
}

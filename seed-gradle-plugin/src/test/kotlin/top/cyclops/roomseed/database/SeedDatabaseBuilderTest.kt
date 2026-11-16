package top.cyclops.roomseed.database

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.cyclops.roomseed.schema.RoomSchemaReader
import top.cyclops.roomseed.schema.SchemaColumn
import top.cyclops.roomseed.schema.SchemaPlan
import top.cyclops.roomseed.seed.SeedDirectory
import top.cyclops.roomseed.seed.SeedFile
import top.cyclops.roomseed.seed.SeedInputException
import top.cyclops.roomseed.sqlite.SqliteDatabase
import top.cyclops.roomseed.sqlite.SqliteException

class SeedDatabaseBuilderTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun icuChineseSearchAndMixedTokenizersSurviveReopen() {
        val file = temporaryFolder.root.toPath().resolve("icu.db")
        SqliteDatabase.open(file).use { db ->
            for (tokenizer in listOf("simple", "porter", "unicode61", "icu")) {
                db.execute("CREATE VIRTUAL TABLE $tokenizer USING fts4(body, tokenize=$tokenizer)", "Creating $tokenizer")
                db.execute("INSERT INTO $tokenizer(rowid, body) VALUES (1, '中文搜索 数据库')", "Importing Chinese")
            }
        }
        SqliteDatabase.open(file).use { db ->
            assertEquals(listOf(listOf("1")), db.query("SELECT rowid FROM icu WHERE icu MATCH '中文'"))
            db.execute("UPDATE icu SET body='北京大学' WHERE rowid=1", "Updating Chinese")
            assertTrue(db.query("SELECT rowid FROM icu WHERE icu MATCH '中文'").isEmpty())
            assertEquals(listOf(listOf("1")), db.query("SELECT rowid FROM icu WHERE icu MATCH '北京大学'"))
            db.execute("INSERT INTO icu(icu) VALUES ('integrity-check')", "Checking ICU index")
        }
    }

    @Test
    fun missingIcuReportsDatabaseTableAndRemedy() {
        val file = temporaryFolder.root.toPath().resolve("missing-icu.db")
        val table = top.cyclops.roomseed.schema.SchemaFtsTable(
            "question_fts", top.cyclops.roomseed.schema.FtsVersion.FTS4,
            "", emptyList(), null, emptyList(), null, "icu",
        )
        val plan = SchemaPlan(1, "hash", emptyList(), emptyList(), listOf(table))
        SqliteDatabase.open(file, enableIcu = false).use { db ->
            val failure = assertThrows(SqliteException::class.java) {
                SeedDatabaseBuilder.checkTokenizers(db, plan, file)
            }
            for (part in listOf("missing-icu.db", "question_fts", "icu", "native package")) {
                assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains(part))
            }
        }
    }

    @Test
    fun buildsAndReopensDemoSchema() {
        val plan = demoPlan()
        val output = temporaryFolder.root.toPath().resolve("catalog.db")
        SeedDatabaseBuilder.build(plan, output)

        SqliteDatabase.open(output, readOnly = true).use { db ->
            assertEquals(listOf(listOf("1")), db.query("PRAGMA user_version"))
            assertEquals(
                listOf(listOf("42", plan.identityHash)),
                db.query("SELECT id, identity_hash FROM room_master_table"),
            )
            assertEquals(
                listOf(listOf("id", "INTEGER", "1"), listOf("name", "TEXT", "0")),
                db.query("SELECT name, type, pk FROM pragma_table_info('products') ORDER BY cid"),
            )
            assertEquals(listOf(listOf("0")), db.query("SELECT count(*) FROM products"))
            assertEquals(listOf(listOf("ok")), db.query("PRAGMA integrity_check"))
        }
        assertEquals(listOf("catalog.db"), temporaryFolder.root.list().orEmpty().toList())
    }

    @Test
    fun buildsSearchableRoom2DictionaryAndKeepsItsIndexInSync() {
        val schema = demoSchema("top.cyclops.roomseed.demo.dictionary.DictionaryDatabase", 2)
        val plan = RoomSchemaReader.read(schema, 2)
        val entries = checkNotNull(javaClass.getResource("/dictionary/entries.jsonl")).readText()
        val seedDirectory = temporaryFolder.newFolder().toPath()
        Files.writeString(seedDirectory.resolve("entries.jsonl"), entries)
        val output = temporaryFolder.root.toPath().resolve("dictionary.db")
        SeedDatabaseBuilder.build(plan, output, SeedDirectory.discover(seedDirectory))

        SqliteDatabase.open(output).use { db ->
            val search = "SELECT word FROM entries_fts WHERE entries_fts MATCH ?"
            assertEquals(listOf(listOf("atlas")), db.query(search, "Searching word", "atlas"))
            assertEquals(
                listOf(listOf("quill")),
                db.query(search, "Searching definition", "\"made from a feather\""),
            )
            db.execute(
                "UPDATE entries SET definition = 'A writing instrument' WHERE word = 'quill'",
                "Updating entry",
            )
            assertTrue(db.query(search, "Searching old definition", "feather").isEmpty())
            assertEquals(listOf(listOf("quill")), db.query(search, "Searching new definition", "writing"))
            db.execute("DELETE FROM entries WHERE word = 'quill'", "Deleting entry")
            assertTrue(db.query(search, "Searching deleted entry", "quill").isEmpty())
        }
    }

    @Test
    fun buildsSearchableRoom3DatabaseAndKeepsItsIndexInSync() {
        val schema = demoSchema("top.cyclops.roomseed.demo.room3.LibraryDatabase", 2)
        val plan = RoomSchemaReader.read(schema, 2)
        val books = checkNotNull(javaClass.getResource("/library/books.jsonl")).readText()
        val seedDirectory = temporaryFolder.newFolder().toPath()
        Files.writeString(seedDirectory.resolve("books.jsonl"), books)
        val output = temporaryFolder.root.toPath().resolve("library.db")
        SeedDatabaseBuilder.build(plan, output, SeedDirectory.discover(seedDirectory))

        SqliteDatabase.open(output).use { db ->
            assertEquals(
                listOf(
                    listOf("1", "A Room of One's Own"),
                    listOf("2", "Pride and Prejudice"),
                    listOf("3", "The Time Machine"),
                ),
                db.query("SELECT id, title FROM books ORDER BY id"),
            )
            assertEquals(
                listOf(listOf(plan.identityHash)),
                db.query("SELECT identity_hash FROM room_master_table WHERE id = 42"),
            )
            val search = "SELECT rowid FROM books_fts WHERE books_fts MATCH ?"
            assertEquals(listOf(listOf("2")), db.query(search, "Searching author", "Austen"))
            assertEquals(listOf(listOf("3")), db.query(search, "Searching phrase", "\"time machine\""))
            assertTrue(db.query(search, "Searching missing word", "missing").isEmpty())

            db.execute("UPDATE books SET title = 'A New Story' WHERE id = 1", "Updating content")
            assertTrue(db.query(search, "Searching old title", "room").isEmpty())
            assertEquals(listOf(listOf("1")), db.query(search, "Searching updated title", "story"))
            db.execute("DELETE FROM books WHERE id = 1", "Deleting content")
            assertTrue(db.query(search, "Searching deleted title", "story").isEmpty())
        }
    }

    @Test
    fun keepsFts5SearchableDuringMixedSeedImport() {
        val plan = RoomSchemaReader.read(demoSchema("top.cyclops.roomseed.demo.room3.LibraryDatabase", 2), 2)
        val seedDirectory = temporaryFolder.newFolder().toPath()
        Files.writeString(seedDirectory.resolve("books.json"), """[{"title":"The Time Machine","author":"H. G. Wells"}]""")
        val books = SeedDirectory.discover(seedDirectory)
        val update = script(
            """
            UPDATE books SET title = 'The Time Traveller'
            WHERE id IN (SELECT rowid FROM books_fts WHERE books_fts MATCH 'machine');
            """.trimIndent(),
        )
        val output = temporaryFolder.root.toPath().resolve("library.db")

        SeedDatabaseBuilder.build(plan, output, books + update)

        SqliteDatabase.open(output, readOnly = true).use { db ->
            assertEquals(
                listOf(listOf("The Time Traveller")),
                db.query("SELECT title FROM books_fts WHERE books_fts MATCH 'traveller'"),
            )
        }
    }

    @Test
    fun appendsBooksToACopyAndPreservesTheBaseDatabase() {
        val plan = RoomSchemaReader.read(demoSchema("top.cyclops.roomseed.demo.room3.LibraryDatabase", 2), 2)
        fun books(resource: String): List<SeedFile> {
            val seed = temporaryFolder.newFolder().toPath()
            Files.writeString(seed.resolve("books.jsonl"), checkNotNull(javaClass.getResource(resource)).readText())
            return SeedDirectory.discover(seed)
        }
        val base = temporaryFolder.root.toPath().resolve("library.db")
        val output = base.resolveSibling("demo.db")
        SeedDatabaseBuilder.build(plan, base, books("/library/books.jsonl"))
        val original = Files.readAllBytes(base)

        // An invalid CREATE statement proves the append path does not execute schema DDL.
        val appendPlan = plan.copy(tables = plan.tables.map { it.copy(createSql = "invalid DDL") })
        SeedDatabaseBuilder.build(appendPlan, output, books("/demo/library/books.jsonl"), base = base)
        assertArrayEquals(original, Files.readAllBytes(base))
        SqliteDatabase.open(output, readOnly = true).use { db ->
            assertEquals(listOf(listOf("4")), db.query("SELECT count(*) FROM books"))
            assertEquals(
                listOf(listOf("The War of the Worlds")),
                db.query("SELECT title FROM books_fts WHERE books_fts MATCH ?", "Searching appended book", "worlds"),
            )
        }
    }

    @Test
    fun importsStandaloneFtsRowsWithOptionalRowId() {
        verifyStandaloneFtsRows("FTS4", "`body` TEXT NOT NULL, tokenize=icu")
        verifyStandaloneFtsRows("FTS5", "`body`, tokenize=`unicode61`")
        verifyStandaloneFtsRows("FTS4", "`body` TEXT NOT NULL, languageid=`lang`", "lang")
    }

    private fun verifyStandaloneFtsRows(version: String, ddl: String, languageId: String? = null) {
        val schema = temporaryFolder.newFile().toPath()
        val languageOption = languageId?.let { ", \"languageIdColumnName\": \"$it\"" }.orEmpty()
        Files.writeString(
            schema,
            """
            {
              "formatVersion": 1,
              "database": {
                "version": 1,
                "identityHash": "0123456789abcdef0123456789abcdef",
                "entities": [{
                  "tableName": "notes",
                  "createSql": "CREATE VIRTUAL TABLE IF NOT EXISTS `${'$'}{TABLE_NAME}` USING $version($ddl)",
                  "fields": [{"fieldPath":"body","columnName":"body","affinity":"TEXT","notNull":true}],
                  "primaryKey": {"autoGenerate":false,"columnNames":["rowid"]},
                  "ftsVersion": "$version",
                  "ftsOptions": {"contentTable": ""$languageOption},
                  "contentSyncTriggers": []
                }],
                "setupQueries": [
                  "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
                  "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '0123456789abcdef0123456789abcdef')"
                ]
              }
            }
            """.trimIndent(),
        )
        val seedDirectory = temporaryFolder.newFolder().toPath()
        val languageValue = languageId?.let { ", \"$it\": 1" }.orEmpty()
        Files.writeString(
            seedDirectory.resolve("notes.jsonl"),
            """
            {"rowid": 12, "body": "First note 中文搜索"$languageValue}
            {"body": "Second note"}
            """.trimIndent(),
        )
        val output = temporaryFolder.root.toPath().resolve("notes-$version.db")
        SeedDatabaseBuilder.build(
            RoomSchemaReader.read(schema, 1), output, SeedDirectory.discover(seedDirectory),
        )
        SqliteDatabase.open(output, readOnly = true).use { db ->
            assertEquals(
                if (languageId == null) listOf(listOf("12"), listOf("13")) else listOf(listOf("13")),
                db.query("SELECT rowid FROM notes WHERE notes MATCH 'note' ORDER BY rowid"),
            )
            if (ddl.contains("tokenize=icu")) {
                assertEquals(listOf(listOf("12")), db.query("SELECT rowid FROM notes WHERE notes MATCH '中文'"))
            }
            if (languageId != null) {
                assertEquals(
                    listOf(listOf("12")),
                    db.query("SELECT rowid FROM notes WHERE notes MATCH 'note' AND lang = 1"),
                )
            }
        }
    }

    @Test
    fun preservesPublishedDatabaseWhenDdlHasExtraStatementsOrWrongColumns() {
        val plan = demoPlan()
        val output = temporaryFolder.root.toPath().resolve("catalog.db")
        SeedDatabaseBuilder.build(plan, output)
        val previous = Files.readAllBytes(output)
        val table = plan.tables.single()
        for ((ddl, reason) in
            listOf(
                (table.createSql + "; DROP TABLE products;") to
                    "unexpected SQL after the first statement",
                table.createSql.replace("`name` TEXT", "`name` BLOB") to
                    "differ from the Room schema",
            )) {
            val badPlan = plan.copy(tables = listOf(table.copy(createSql = ddl)))
            val failure =
                assertThrows(SqliteException::class.java) {
                    SeedDatabaseBuilder.build(badPlan, output)
                }
            assertTrue(failure.message, failure.message.orEmpty().contains(reason))
            assertArrayEquals(previous, Files.readAllBytes(output))
            assertEquals(listOf("catalog.db"), temporaryFolder.root.list().orEmpty().toList())
        }
    }

    @Test
    fun runsDemoSeedAndLaterScriptsInOrder() {
        val demo = script(checkNotNull(javaClass.getResource("/catalog/catalog.sql")).readText())
        val later =
            script(
                """
            -- Literal semicolons and escaped quotes stay in the value.
            WITH updates(id, name) AS (VALUES (1, 'Notebook; it''s new'))
            INSERT INTO products SELECT id, name FROM updates WHERE true
            ON CONFLICT(id) DO UPDATE SET name = excluded.name;
            INSERT INTO products SELECT id + 2, name FROM products WHERE id = 1 RETURNING id;
            DELETE FROM products WHERE id = 3;
            /* trailing comment */
            UPDATE products SET name = upper(name) WHERE id = 2
        """
                    .trimIndent(),
                "10-later.sql",
            )
        val output = temporaryFolder.root.toPath().resolve("catalog.db")
        SeedDatabaseBuilder.build(demoPlan(), output, listOf(demo, later))
        SqliteDatabase.open(output, readOnly = true).use { db ->
            assertEquals(
                listOf(listOf("1", "Notebook; it's new"), listOf("2", "FOUNTAIN PEN")),
                db.query("SELECT id, name FROM products ORDER BY id"),
            )
        }
    }

    @Test
    fun executesTrustedSqlWithTriggersAndPragmas() {
        val output = temporaryFolder.root.toPath().resolve("catalog.db")
        val seed =
            script(
                """
            PRAGMA defer_foreign_keys = ON;
            CREATE TEMP TABLE audit (name TEXT);
            CREATE TEMP TRIGGER audit_products AFTER INSERT ON products BEGIN
                INSERT INTO audit VALUES (CASE WHEN new.id = 1 THEN 'first; product' ELSE new.name END);
                SELECT name FROM sqlite_schema;
            END;
            INSERT INTO products VALUES (1, 'Notebook');
            INSERT INTO products SELECT id + 1, upper(name) FROM products WHERE id = 1;
            UPDATE products SET name = (SELECT name FROM audit WHERE rowid = 1) WHERE id = 1;
            -- The trailing comment is not an empty statement.
        """
                    .trimIndent()
            )
        SeedDatabaseBuilder.build(demoPlan(), output, listOf(seed))
        SqliteDatabase.open(output, readOnly = true).use { db ->
            assertEquals(
                listOf(listOf("first; product"), listOf("NOTEBOOK")),
                db.query("SELECT name FROM products ORDER BY id"),
            )
        }
    }

    @Test
    fun rejectsSchemaChangesInFreshAndAppendedDatabases() {
        val plan = demoPlan()
        val output = temporaryFolder.root.toPath().resolve("catalog.db")
        val base = output.resolveSibling("base.db")
        val original = script("INSERT INTO products VALUES (42, 'Old');")
        SeedDatabaseBuilder.build(plan, base, listOf(original))
        SeedDatabaseBuilder.build(plan, output, listOf(original))
        val previous = Files.readAllBytes(output)
        val originalBase = Files.readAllBytes(base)
        val changes = mapOf(
            "ALTER TABLE products ADD COLUMN extra TEXT;" to "differ from the Room schema",
            "DROP TABLE products; CREATE TABLE products (id INTEGER NOT NULL PRIMARY KEY, name TEXT NOT NULL UNIQUE);" to
                "object definitions changed during seed import",
        )
        for (source in listOf(null, base)) {
            for ((sql, expectedMessage) in changes) {
                val failure = assertThrows(SqliteException::class.java) {
                    SeedDatabaseBuilder.build(plan, output, listOf(script(sql)), base = source)
                }
                assertTrue(failure.message, failure.message.orEmpty().contains(expectedMessage))
                assertArrayEquals(previous, Files.readAllBytes(output))
                assertArrayEquals(originalBase, Files.readAllBytes(base))
            }
        }
    }

    @Test
    fun importsDifferentColumnSetsAndKeepsExplicitNulls() {
        val plan = demoPlan()
        val products = plan.tables.single()
        val table = products.copy(
            createSql = products.createSql.replace("PRIMARY KEY", "`note` TEXT DEFAULT 'default', PRIMARY KEY"),
            columns = products.columns + SchemaColumn("note", "note", "TEXT", false, "'default'"),
        )
        val csv = rowSeed("csv", "id,name\n1,CSV\n")
        val jsonl = rowSeed(
            "jsonl",
            """
            {"id":2,"name":"JSONL","note":"value"}
            {"name":"reverse key order","note":null,"id":3}
            {"id":4,"name":"omitted note"}
            {"id":5,"name":"explicit note again","note":"new value"}
            """.trimIndent(),
        )
        val json = rowSeed("json", """[{"id":6,"name":"JSON"},{"id":7,"name":"explicit null","note":null}]""")
        val output = temporaryFolder.root.toPath().resolve("catalog.db")

        val seeds = SeedDirectory.discover(json.file.parent, listOf("products.csv", "products.jsonl", "*.json"))
        assertEquals(listOf(csv, jsonl, json), seeds)
        SeedDatabaseBuilder.build(plan.copy(tables = listOf(table)), output, seeds)

        SqliteDatabase.open(output, readOnly = true).use { db ->
            assertEquals(
                listOf("default", "value", null, "default", "new value", "default", null).map { listOf(it) },
                db.query("SELECT note FROM products ORDER BY id"),
            )
        }
    }

    @Test
    fun reportsTheFailingRowWhenAReusedInsertViolatesAConstraint() {
        val plan = demoPlan()
        val output = temporaryFolder.root.toPath().resolve("catalog.db")
        SeedDatabaseBuilder.build(plan, output)
        val previous = Files.readAllBytes(output)
        val csv = rowSeed("csv", "id,name\n1,First\n1,Duplicate\n")
        val jsonl = rowSeed(
            "jsonl",
            """
            {"id":1,"name":"First"}
            {"id":1,"name":"Duplicate"}
            """.trimIndent(),
        )

        val json = rowSeed("json", """[{"id":1,"name":"First"},{"id":1,"name":"Duplicate"}]""")
        for ((seed, location) in listOf(csv to "products.csv:3", jsonl to "products.jsonl:2", json to "products.json: item 2")) {
            val failure = assertThrows(SqliteException::class.java) {
                SeedDatabaseBuilder.build(plan, output, listOf(seed))
            }
            assertTrue(failure.message, failure.message.orEmpty().startsWith("$location: cannot execute statement"))
            assertArrayEquals(previous, Files.readAllBytes(output))
        }
    }

    @Test
    fun requiresOneJsonArrayOfObjects() {
        val output = temporaryFolder.root.toPath().resolve("catalog.db")
        val seed = rowSeed("json", "[]")
        val plan = demoPlan()
        SeedDatabaseBuilder.build(plan, output, listOf(seed))
        val previous = Files.readAllBytes(output)

        val invalid = mapOf(
            "{}" to "expected a JSON array of objects",
            "[{}, 1]" to "missing required column",
            """[{"id":1,"name":"first"}, null]""" to "item 2: expected a JSON object",
            """[{"id":1,"id":2}]""" to "item 1: duplicate column 'id'",
            "[] []" to "products.json:",
        )
        for ((contents, expected) in invalid) {
            Files.writeString(seed.file, contents)
            val error = assertThrows(SeedInputException::class.java) {
                SeedDatabaseBuilder.build(plan, output, listOf(seed))
            }
            assertTrue(error.message, error.message.orEmpty().contains(expected))
            assertArrayEquals(previous, Files.readAllBytes(output))
        }
    }

    @Test
    fun rollsBackFailedSeedScriptsAndPreservesOldDatabase() {
        val plan = demoPlan()
        val output = temporaryFolder.root.toPath().resolve("catalog.db")
        SeedDatabaseBuilder.build(
            plan,
            output,
            listOf(script("INSERT INTO products VALUES (42, 'Old');")),
        )
        val previous = Files.readAllBytes(output)
        val first = script("\uFEFFINSERT INTO products VALUES (1, 'New');")
        val failures =
            mapOf(
                "INSERT INTO products VALUES (1, 'Duplicate');" to "cannot execute statement",
                "COMMIT;" to "ended the import transaction",
                "INSERT INTO products VALUES (2," to "cannot execute statement",
            )
        for ((sql, reason) in failures) {
            val second = script("-- Second script\n$sql", "10-fail.sql")
            val failure =
                assertThrows(SqliteException::class.java) {
                    SeedDatabaseBuilder.build(plan, output, listOf(first, second))
                }
            assertTrue(failure.message, failure.message.orEmpty().contains("10-fail.sql' at 2:"))
            assertTrue(failure.message, failure.message.orEmpty().contains(reason))
            assertArrayEquals(previous, Files.readAllBytes(output))
        }
        assertEquals(
            emptyList<String>(),
            output.parent.toFile().list().orEmpty().filter { it.startsWith(".room-seed-") },
        )
    }

    private fun rowSeed(format: String, contents: String): SeedFile {
        val name = "products.$format"
        val file = temporaryFolder.newFile(name).toPath()
        Files.writeString(file, contents)
        return SeedFile(name, file, format)
    }

    private fun script(contents: String, name: String = "catalog.sql"): SeedFile {
        val file = temporaryFolder.newFile().toPath()
        Files.writeString(file, contents)
        return SeedFile(name, file)
    }

    private fun demoPlan(): SchemaPlan {
        val schema = demoSchema("top.cyclops.roomseed.demo.catalog.CatalogDatabase")
        return try {
            RoomSchemaReader.read(schema, 1)
        } finally {
            Files.delete(schema)
        }
    }

    private fun demoSchema(database: String, version: Int = 1) = temporaryFolder.newFile().toPath().also { schema ->
        val export = checkNotNull(javaClass.getResource("/$database/$version.json"))
        Files.writeString(schema, export.readText())
    }
}

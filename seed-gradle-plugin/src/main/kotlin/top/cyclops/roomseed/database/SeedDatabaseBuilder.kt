package top.cyclops.roomseed.database

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import top.cyclops.roomseed.schema.FtsVersion
import top.cyclops.roomseed.schema.SchemaFtsTable
import top.cyclops.roomseed.schema.SchemaPlan
import top.cyclops.roomseed.seed.SeedFile
import top.cyclops.roomseed.seed.format.SeedFormats
import top.cyclops.roomseed.sqlite.SqliteDatabase
import top.cyclops.roomseed.sqlite.SqliteException
import top.cyclops.roomseed.sqlite.quoteIdentifier

internal object SeedDatabaseBuilder {
    fun build(
        plan: SchemaPlan,
        output: Path,
        seedFiles: List<SeedFile> = emptyList(),
        stagingParent: Path = output.parent,
        base: Path? = null,
    ) {
        Files.createDirectories(stagingParent)
        val staging = Files.createTempDirectory(stagingParent, ".room-seed-")
        val databaseFile = staging.resolve("seed.db")
        var failure: Throwable? = null
        try {
            if (base != null) {
                requireStandaloneFile(base)
                Files.copy(base, databaseFile)
                SqliteDatabase.open(databaseFile, readOnly = true).use { verifyMetadata(it, plan) }
            }
            populateDatabase(databaseFile, plan, seedFiles, createSchema = base == null)
            SqliteDatabase.open(databaseFile, readOnly = true).use { verifyMetadata(it, plan) }
            Files.newDirectoryStream(staging).use { files ->
                requireDatabase(
                    files.toList() == listOf(databaseFile),
                    "Unexpected SQLite companion files",
                )
            }
            requireStandaloneFile(output)
            Files.createDirectories(output.parent)
            Files.move(databaseFile, output, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                Files.newDirectoryStream(staging).use { files ->
                    files.forEach { Files.deleteIfExists(it) }
                }
                Files.deleteIfExists(staging)
            } catch (cleanup: Exception) {
                if (failure == null) throw cleanup
                failure.addSuppressed(cleanup)
            }
        }
    }

    fun requireStandaloneFile(file: Path) {
        for (suffix in listOf("-journal", "-wal", "-shm")) {
            requireDatabase(
                !Files.exists(file.resolveSibling("${file.fileName}$suffix"), NOFOLLOW_LINKS),
                "Database $file has a $suffix companion file",
            )
        }
    }

    private fun verifyMetadata(db: SqliteDatabase, plan: SchemaPlan) {
        requireDatabase(
            db.query("PRAGMA user_version") == listOf(listOf(plan.version.toString())),
            "Unexpected user_version",
        )
        requireDatabase(
            db.query("SELECT id, identity_hash FROM room_master_table") ==
                listOf(listOf("42", plan.identityHash)),
            "Unexpected Room metadata",
        )
    }

    private fun populateDatabase(
        databaseFile: Path,
        plan: SchemaPlan,
        seedFiles: List<SeedFile>,
        createSchema: Boolean,
    ) {
        SqliteDatabase.open(databaseFile).use { db ->
            requireDatabase(
                db.query("PRAGMA journal_mode = DELETE") == listOf(listOf("delete")),
                "Expected DELETE journal mode",
            )
            db.execute("PRAGMA foreign_keys = ON", "Enabling foreign keys")
            requireDatabase(
                db.query("PRAGMA foreign_keys") == listOf(listOf("1")),
                "Foreign keys are disabled",
            )
            db.transaction {
                if (createSchema) createSchema(db, plan)
                val originalDefinitions = if (seedFiles.any { it.format == "sql" }) schemaDefinitions(db) else null
                // Structured seeds write content rows without querying the FTS index during import.
                val deferredIndexes = if (createSchema && seedFiles.isNotEmpty() && originalDefinitions == null) {
                    plan.ftsTables.filter { it.version == FtsVersion.FTS5 && it.contentTable != null }
                } else emptyList()
                for (fts in deferredIndexes) {
                    for (trigger in fts.triggers) {
                        db.execute("DROP TRIGGER ${quoteIdentifier(trigger.name)}", "Deferring FTS trigger '${trigger.name}'")
                    }
                }
                for (file in seedFiles) {
                    val reader = SeedFormats.rowReader(file.format)
                    if (reader == null) {
                        db.executeSeed(file.readText(), file.relativePath)
                    } else {
                        db.withReusableInserts { execute ->
                            reader.read(file, SeedRowImporter(plan, execute)::insert)
                        }
                    }
                }
                for (fts in deferredIndexes) {
                    val table = quoteIdentifier(fts.name)
                    db.execute("INSERT INTO $table ($table) VALUES ('rebuild')", "Rebuilding FTS5 index '${fts.name}'")
                    createFtsTriggers(db, fts)
                }
                RoomSchemaVerifier.verify(db, plan, includesRoomMetadata = !createSchema)
                if (originalDefinitions != null) {
                    requireDatabase(
                        schemaDefinitions(db) == originalDefinitions,
                        "SQLite object definitions changed during seed import",
                    )
                }
                for (fts in plan.ftsTables) {
                    val table = quoteIdentifier(fts.name)
                    val check = if (fts.version == FtsVersion.FTS5 && fts.contentTable != null) {
                        "INSERT INTO $table ($table, rank) VALUES ('integrity-check', 1)"
                    } else {
                        "INSERT INTO $table ($table) VALUES ('integrity-check')"
                    }
                    db.execute(check, "Checking ${fts.version} index '${fts.name}'")
                }
                if (createSchema) writeMetadata(db, plan)
                requireDatabase(
                    db.query("PRAGMA foreign_key_check").isEmpty(),
                    "Foreign key check failed",
                )
                requireDatabase(
                    db.query("PRAGMA integrity_check") == listOf(listOf("ok")),
                    "SQLite integrity check failed",
                )
            }
        }
    }

    private fun schemaDefinitions(db: SqliteDatabase) = db.query(
        "SELECT type, name, tbl_name, sql FROM main.sqlite_schema WHERE name NOT GLOB 'sqlite_*' ORDER BY type, name",
        "Reading SQLite object definitions",
    )

    private fun createSchema(db: SqliteDatabase, plan: SchemaPlan) {
        for (table in plan.tables) db.executeSingleStatement(
            table.createSql,
            "Creating table '${table.name}'",
        )
        for (table in plan.tables) {
            for (index in table.indices) db.executeSingleStatement(
                index.createSql,
                "Creating index '${index.name}'",
            )
        }
        for (fts in plan.ftsTables) db.executeSingleStatement(
            fts.createSql,
            "Creating ${fts.version} table '${fts.name}'",
        )
        for (view in plan.views) db.executeSingleStatement(
            view.createSql,
            "Creating view '${view.name}'",
        )
        for (fts in plan.ftsTables) {
            createFtsTriggers(db, fts)
        }
        // Verify the DDL before an INSERT can evaluate a column default.
        RoomSchemaVerifier.verify(db, plan)
    }

    private fun createFtsTriggers(db: SqliteDatabase, fts: SchemaFtsTable) {
        for (trigger in fts.triggers) db.executeSingleStatement(
            trigger.createSql,
            "Creating ${fts.version} trigger '${trigger.name}'",
        )
    }

    private fun writeMetadata(db: SqliteDatabase, plan: SchemaPlan) {
        db.execute(
            "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
            "Creating Room metadata",
        )
        db.execute(
            "INSERT INTO room_master_table (id, identity_hash) VALUES (42, ?)",
            "Writing Room identity hash",
            plan.identityHash,
        )
        requireDatabase(plan.version > 0, "Invalid Room database version")
        db.execute("PRAGMA user_version = ${plan.version}", "Writing Room database version")
    }
}

internal fun requireDatabase(condition: Boolean, message: String) {
    if (!condition) throw SqliteException(message)
}

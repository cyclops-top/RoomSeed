package top.cyclops.roomseed.sqlite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Path

internal class SqliteException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal class SqliteDatabase private constructor(private val db: SQLiteConnection) :
    AutoCloseable {
    companion object {
        private const val OPEN_READ_ONLY = 1
        private const val OPEN_READ_WRITE = 2
        private const val OPEN_CREATE = 4

        fun open(file: Path, readOnly: Boolean = false, enableIcu: Boolean = true): SqliteDatabase {
            if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true) &&
                System.getProperty("os.arch") in listOf("x86_64", "amd64")) {
                throw SqliteException("Opening database '$file': macOS Intel is not supported by RoomSeed's BundledSQLiteDriver 2.7.1. Use macOS Apple Silicon with an arm64 JVM, Linux arm64/x64, or Windows x64.")
            }
            val flags = if (readOnly) OPEN_READ_ONLY else OPEN_READ_WRITE or OPEN_CREATE
            return try {
                SqliteDatabase((if (enableIcu) IcuExtension.driver() else BundledSQLiteDriver()).open(file.toAbsolutePath().toString(), flags))
            } catch (failure: SQLiteException) {
                throw SqliteException("Opening database '$file': ${failure.message}", failure)
            }
        }
    }

    fun execute(sql: String, label: String, argument: String? = null) {
        run(sql, label, listOfNotNull(argument), false)
    }

    fun withReusableInserts(importRows: ((String, String, List<Any?>) -> Unit) -> Unit) {
        InsertStatements().use { importRows(it::execute) }
    }

    fun query(sql: String, label: String = sql, vararg arguments: String): List<List<String?>> =
        run(sql, label, arguments.asList(), true)

    fun validateView(name: String) {
        query("SELECT * FROM ${quoteIdentifier(name)} LIMIT 0")
    }

    fun executeSingleStatement(sql: String, label: String) {
        val statements = sqlStatements(sql).iterator()
        if (!statements.hasNext()) throw SqliteException("$label contains no SQL statement")
        val statement = statements.next()
        if (statements.hasNext())
            throw SqliteException("$label contains unexpected SQL after the first statement")
        execute(statement.sql, label)
    }

    fun executeSeed(script: String, path: String) {
        if ('\u0000' in script)
            throw SqliteException("Seed script '$path' contains a NUL character")
        for (statement in sqlStatements(script)) {
            val label = "Seed script '$path' at ${statement.line}:${statement.column}"
            execute(statement.sql, label)
            if (!db.inTransaction()) throw SqliteException("$label ended the import transaction")
        }
    }

    fun transaction(action: () -> Unit) {
        execute("BEGIN", "Beginning transaction")
        try {
            action()
            execute("COMMIT", "Committing database")
        } catch (failure: Throwable) {
            if (db.inTransaction()) {
                try {
                    execute("ROLLBACK", "Rolling back database")
                } catch (rollback: Throwable) {
                    failure.addSuppressed(rollback)
                }
            }
            throw failure
        }
    }

    private fun run(
        sql: String,
        label: String,
        args: List<Any?>,
        collect: Boolean,
    ): List<List<String?>> =
        withSqliteError(label) {
            db.prepare(sql).use { stmt ->
                stmt.bind(args)
                buildList {
                    while (stmt.step()) if (collect) {
                        add(
                            (0 until stmt.getColumnCount()).map {
                                if (stmt.isNull(it)) null else stmt.getText(it)
                            }
                        )
                    }
                }
            }
        }

    private inner class InsertStatements : AutoCloseable {
        private val limit = 32
        private val statements = LinkedHashMap<String, SQLiteStatement>(limit, 0.75f, true)

        fun execute(sql: String, label: String, arguments: List<Any?>) = withSqliteError(label) {
            val statement = statements.getOrPut(sql) {
                // JSONL can supply a different column subset on every row.
                if (statements.size == limit) {
                    val oldest = statements.values.iterator()
                    val evicted = oldest.next()
                    oldest.remove()
                    evicted.close()
                }
                db.prepare(sql)
            }
            // Every placeholder is rebound, including explicit NULLs.
            statement.bind(arguments)
            while (statement.step()) Unit
            statement.reset()
        }

        override fun close() = statements.values.forEach(SQLiteStatement::close)
    }

    private fun SQLiteStatement.bind(args: List<Any?>) {
        args.forEachIndexed { i, arg ->
            val n = i + 1
            when (arg) {
                null -> bindNull(n)
                is String -> bindText(n, arg)
                is Long -> bindLong(n, arg)
                is Double -> bindDouble(n, arg)
                is ByteArray -> bindBlob(n, arg)
                else -> error("Unsupported argument")
            }
        }
    }

    private inline fun <T> withSqliteError(label: String, action: () -> T): T = try {
        action()
    } catch (failure: SQLiteException) {
        throw SqliteException("$label: cannot execute statement: ${failure.message}", failure)
    }

    override fun close() = db.close()
}

internal fun quoteIdentifier(name: String): String = "\"${name.replace("\"", "\"\"")}\""

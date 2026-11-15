package top.cyclops.roomseed.schema

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RoomSchemaReaderTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun readsDemoExport() {
        val plan = read(demoExport())
        val products = plan.tables.single()
        assertEquals(1, plan.version)
        assertEquals("products", products.name)
        assertTrue(products.createSql.startsWith("CREATE TABLE IF NOT EXISTS `products` ("))
        assertEquals(listOf("id", "name"), products.columns.map { it.name })
        assertEquals(listOf("id"), products.primaryKey.columnNames)
        assertFalse(products.primaryKey.autoGenerate)
        assertTrue(plan.indices.isEmpty())
        assertTrue(plan.views.isEmpty())
    }

    @Test
    fun rejectsDuplicateJsonKeysAndWrongTypes() {
        val source = demoExport()
        val duplicate =
            source.replace("\"formatVersion\": 1", "\"formatVersion\": 1, \"formatVersion\": 1")
        assertInvalid(duplicate, "duplicate JSON key")
        val wrongType = source.replace("\"version\": 1", "\"version\": \"1\"")
        assertInvalid(wrongType, "expected a 32-bit integer")
    }

    @Test
    fun rejectsVersionMismatchAndUnexpectedSetup() {
        val source = demoExport()
        assertInvalid(source, "version 1 does not match filename version 2", version = 2)
        assertInvalid(source.replace("VALUES(42,", "VALUES(43,"), "room_master_table setup queries")
    }

    @Test
    fun rejectsVirtualTablesAndUnknownFields() {
        val source = demoExport()
        assertInvalid(
            source.replace("CREATE TABLE IF NOT EXISTS `", "CREATE VIRTUAL TABLE `"),
            "virtual tables",
        )
        assertInvalid(
            source.replace("\"tableName\":", "\"extra\": true, \"tableName\":"),
            "unsupported fields: extra",
        )
    }

    @Test
    fun readsWithoutRowIdAndRejectsUnknownPrimaryKeyAlgorithm() {
        val source =
            demoExport()
                .replace("\"tableName\":", "\"withoutRowId\": true, \"tableName\":")
                .replace("PRIMARY KEY(`id`))", "PRIMARY KEY(`id`)) WITHOUT ROWID")
        val table = read(source).tables.single()
        assertTrue(table.withoutRowId)
        assertTrue(table.createSql.endsWith("WITHOUT ROWID"))
        assertInvalid(
            source.replace("\"autoGenerate\":", "\"algorithm\": \"unknown\", \"autoGenerate\":"),
            "unsupported Room primary key algorithm",
        )
    }

    @Test
    fun rejectsInvalidUtf8() {
        val file = temporaryFolder.newFile().toPath()
        Files.write(file, byteArrayOf(0xff.toByte()))
        val failure =
            assertThrows(SchemaFormatException::class.java) { RoomSchemaReader.read(file, 1) }
        assertTrue(failure.message.orEmpty().contains("valid UTF-8"))
    }

    private fun demoExport(): String =
        checkNotNull(
                javaClass.getResource("/top.cyclops.roomseed.demo.catalog.CatalogDatabase/1.json")
            )
            .readText()

    private fun read(source: String, version: Int = 1): SchemaPlan {
        val file = temporaryFolder.newFile().toPath()
        Files.writeString(file, source)
        return RoomSchemaReader.read(file, version)
    }

    private fun assertInvalid(source: String, message: String, version: Int = 1) {
        val failure = assertThrows(SchemaFormatException::class.java) { read(source, version) }
        assertTrue(failure.message, failure.message.orEmpty().contains(message))
    }
}

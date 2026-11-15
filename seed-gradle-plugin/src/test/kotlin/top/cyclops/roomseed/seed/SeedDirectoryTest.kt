package top.cyclops.roomseed.seed

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class SeedDirectoryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun expandsManifestPatternsInOrderAndSortsEachMatch() {
        val root = temp.root.toPath()
        Files.createDirectories(root.resolve("nested/extra"))
        val paths = listOf("nested/extra/products.csv", "nested/categories.csv", "setup.sql", "products.jsonl")
        paths.forEach { Files.writeString(root.resolve(it), "") }
        Files.writeString(root.resolve("extra.json"), "[]")
        Files.writeString(root.resolve("index.txt"), "setup.sql")

        assertEquals((paths + "extra.json").sorted(), SeedDirectory.discover(root).map { it.relativePath })

        val manifest = SeedManifest.parse(
            """
            database: example.CatalogDatabase
            databaseFilePath: offline/catalog.db
            files:
              - 'setup.sql'
              - 'nested/**/*.csv'
            """.trimIndent(),
            "seek.yaml",
        )
        assertEquals("example.CatalogDatabase", manifest.database)
        assertEquals("offline/catalog.db", manifest.databaseFilePath)
        assertEquals(
            listOf("setup.sql", "nested/categories.csv", "nested/extra/products.csv"),
            SeedDirectory.discover(root, manifest.files).map { it.relativePath },
        )
    }

    @Test
    fun rejectsDuplicateKeysAndInvalidFileLists() {
        val root = temp.root.toPath()
        Files.writeString(root.resolve("setup.sql"), "")
        for (contents in listOf(
            "databaseFilePath: a.db\ndatabaseFilePath: b.db",
            "database: example.CatalogDatabase\nfiles: [setup.sql]",
            "database: example.CatalogDatabase\ndatabaseFilePath: a.db\nfiles: []",
        )) {
            assertThrows(SeedInputException::class.java) { SeedManifest.parse(contents, "seek.yaml") }
        }
        for (files in listOf(listOf("*.csv"), listOf("setup.sql", "**/*.sql"), listOf("../setup.sql"))) {
            assertThrows(SeedInputException::class.java) { SeedDirectory.discover(root, files) }
        }
    }

    @Test
    fun nestedManifestsOwnTheirSeedFiles() {
        val root = temp.root.toPath()
        val nested = Files.createDirectory(root.resolve("nested"))
        Files.writeString(root.resolve("setup.sql"), "")
        Files.writeString(nested.resolve("products.csv"), "")
        assertEquals(
            listOf("setup.sql"),
            SeedDirectory.discover(root, excludedDirectories = setOf(nested)).map { it.relativePath },
        )
        assertThrows(SeedInputException::class.java) {
            SeedDirectory.discover(root, listOf("nested/products.csv"), setOf(nested))
        }
    }
}

package top.cyclops.roomseed.sqlite

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files

/** The extension adds FTS4 ICU only; it does not replace SQLite or alter SQL. */
internal object IcuExtension {
    private val library: String? by lazy {
        val platform = resourcePath(System.getProperty("os.name"), System.getProperty("os.arch"))
            ?: return@lazy null
        val resource = IcuExtension::class.java.getResourceAsStream("/native/$platform")
            ?: return@lazy null
        val directory = Files.createTempDirectory("roomseed-icu-")
        val file = directory.resolve(platform.substringAfterLast('/'))
        directory.toFile().deleteOnExit()
        resource.use { Files.copy(it, file) }
        file.toFile().deleteOnExit()
        file.toAbsolutePath().toString()
    }

    internal fun resourcePath(osName: String, architecture: String): String? {
        val arch = when (architecture.lowercase(java.util.Locale.ROOT)) {
            "aarch64", "arm64" -> "aarch64"
            "amd64", "x86_64" -> "x86_64"
            else -> return null
        }
        val os = osName.lowercase(java.util.Locale.ROOT)
        return when {
            os.startsWith("mac") -> "macos-$arch/libroomseedicu.dylib"
            os == "linux" -> "linux-$arch/libroomseedicu.so"
            os.startsWith("windows") && arch == "x86_64" -> "windows-$arch/roomseedicu.dll"
            else -> null
        }
    }

    fun driver(): BundledSQLiteDriver = BundledSQLiteDriver().apply {
        library?.let { addExtension(it, "sqlite3_roomseedicu_init") }
    }
}

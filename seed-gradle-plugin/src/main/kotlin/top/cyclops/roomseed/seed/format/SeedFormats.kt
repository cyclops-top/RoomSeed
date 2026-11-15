package top.cyclops.roomseed.seed.format

internal object SeedFormats {
    private const val SQL = "sql"

    private val readers =
        listOf(CsvSeedReader, JsonlSeedReader, JsonSeedReader).associateBy(SeedRowReader::extension)

    val extensions: Set<String> = readers.keys + SQL

    fun rowReader(extension: String): SeedRowReader? = readers[extension]
}

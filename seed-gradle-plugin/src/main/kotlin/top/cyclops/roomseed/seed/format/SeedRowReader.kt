package top.cyclops.roomseed.seed.format

import top.cyclops.roomseed.seed.SeedFile

internal interface SeedRowReader {
    val extension: String

    fun read(file: SeedFile, emit: (SeedRow) -> Unit)
}

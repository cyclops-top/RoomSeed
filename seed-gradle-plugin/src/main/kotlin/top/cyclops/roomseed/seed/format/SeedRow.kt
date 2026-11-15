package top.cyclops.roomseed.seed.format

internal data class SeedRow(
    val table: String,
    val cells: Map<String, SeedValue>,
    val location: String,
)

internal sealed interface SeedValue {
    data class Text(val value: String) : SeedValue

    data class Number(val value: String) : SeedValue

    data class RawText(val value: String) : SeedValue

    data object Null : SeedValue
}

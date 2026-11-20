package dev.flashblaze.budsonwatch.protocol

/**
 * Noise control of the OnePlus Buds Pro 3 (product id `064014`).
 *
 * The buds take a SET bit and answer with a REPORT bit, and the two tables differ: Off is SET bit 0
 * but reported as bit 3, Transparency is SET bit 2 but reported as bit 8. Bits come from HeyMelody's
 * mode tree for this model (QuickBuds `assets/models.json`, `064014`):
 *
 * | Mode           | SET bit | REPORT bits |
 * |----------------|---------|-------------|
 * | Off            | 0       | 0, 3        |
 * | ANC last level | 1       | 1           |
 * | Transparency   | 2       | 2, 8, 9     |
 * | High           | 4       | 4           |
 * | Medium         | 5       | 5           |
 * | Light          | 6       | 6           |
 * | Smart          | 7       | 7           |
 */
enum class AncLevel(val setBit: Int) {
    SMART(7),
    HIGH(4),
    MEDIUM(5),
    LIGHT(6),
}

enum class NoiseMode(val setBit: Int) {
    OFF(0),
    ANC(1),
    TRANSPARENCY(2),
}

/** What the buds report. [level] is null for Off/Transparency, and for ANC when the buds name no level. */
data class NoiseState(val mode: NoiseMode, val level: AncLevel? = null) {
    init {
        require(level == null || mode == NoiseMode.ANC)
    }

    /** The bit to SET for this state: a level's own bit, else the mode's. */
    val setBit: Int get() = level?.setBit ?: mode.setBit

    /** Stable name for storage and tile click ids: the level's name, else the mode's. */
    val key: String get() = level?.name ?: mode.name

    companion object {
        /** Decodes a REPORT mask by its lowest set bit; null for a bit this model does not have. */
        fun fromReport(mask: Int): NoiseState? {
            if (mask <= 0) return null
            return when (Integer.numberOfTrailingZeros(mask)) {
                0, 3 -> NoiseState(NoiseMode.OFF)
                1 -> NoiseState(NoiseMode.ANC)
                2, 8, 9 -> NoiseState(NoiseMode.TRANSPARENCY)
                4 -> NoiseState(NoiseMode.ANC, AncLevel.HIGH)
                5 -> NoiseState(NoiseMode.ANC, AncLevel.MEDIUM)
                6 -> NoiseState(NoiseMode.ANC, AncLevel.LIGHT)
                7 -> NoiseState(NoiseMode.ANC, AncLevel.SMART)
                else -> null
            }
        }

        /** The level Smart picked (`03 04 01 <mask>`), named in SET bits. Smart itself is not a pick. */
        fun smartPick(mask: Int): AncLevel? {
            if (mask <= 0) return null
            val bit = Integer.numberOfTrailingZeros(mask)
            return AncLevel.entries.firstOrNull { it != AncLevel.SMART && it.setBit == bit }
        }

        fun fromKey(key: String?): NoiseState? {
            AncLevel.entries.firstOrNull { it.name == key }?.let { return NoiseState(NoiseMode.ANC, it) }
            return NoiseMode.entries.firstOrNull { it.name == key }?.let { NoiseState(it) }
        }
    }
}

/**
 * ANC levels per product id (`0x8103`). The buds do not list their levels; HeyMelody keeps a
 * per-model table, and a wrong bit silently sets another mode, so unknown models get no levels.
 */
object Models {
    const val BUDS_PRO_3 = "064014"

    fun levels(productId: String?): List<AncLevel> = when (productId) {
        // HeyMelody's tree for 064014: noise reduction -> Smart 7, strong 4, middle 5, weak 6.
        BUDS_PRO_3 -> listOf(AncLevel.SMART, AncLevel.HIGH, AncLevel.MEDIUM, AncLevel.LIGHT)
        else -> emptyList()
    }
}

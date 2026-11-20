package dev.flashblaze.budsonwatch

import android.content.Context
import dev.flashblaze.budsonwatch.protocol.AncLevel
import dev.flashblaze.budsonwatch.protocol.NoiseMode
import dev.flashblaze.budsonwatch.protocol.NoiseState

fun AncLevel.labelRes(): Int = when (this) {
    AncLevel.SMART -> R.string.level_smart
    AncLevel.HIGH -> R.string.level_high
    AncLevel.MEDIUM -> R.string.level_medium
    AncLevel.LIGHT -> R.string.level_light
}

/** Short names for the tile's level pills. */
fun AncLevel.tileLabelRes(): Int = when (this) {
    AncLevel.SMART -> R.string.level_smart
    AncLevel.HIGH -> R.string.level_high
    AncLevel.MEDIUM -> R.string.tile_level_medium
    AncLevel.LIGHT -> R.string.level_light
}

fun NoiseMode.labelRes(): Int = when (this) {
    NoiseMode.OFF -> R.string.mode_off
    NoiseMode.ANC -> R.string.mode_anc
    NoiseMode.TRANSPARENCY -> R.string.mode_transparency
}

fun NoiseState?.label(context: Context): String = when {
    this == null -> context.getString(R.string.unknown_mode)
    level != null -> context.getString(R.string.anc_with_level, context.getString(level.labelRes()))
    else -> context.getString(mode.labelRes())
}

fun Notice.labelRes(): Int = when (this) {
    Notice.NOT_WORN -> R.string.notice_not_worn
    Notice.REFUSED -> R.string.notice_refused
    Notice.NO_REPLY -> R.string.notice_no_reply
}

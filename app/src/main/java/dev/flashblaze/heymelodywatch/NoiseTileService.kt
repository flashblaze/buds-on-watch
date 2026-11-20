package dev.flashblaze.heymelodywatch

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders.Background
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ModifiersBuilders.Corner
import androidx.wear.protolayout.ModifiersBuilders.Modifiers
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.flashblaze.heymelodywatch.protocol.AncLevel
import dev.flashblaze.heymelodywatch.protocol.NoiseMode
import dev.flashblaze.heymelodywatch.protocol.NoiseState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Off / ANC / Transparency buttons. A tap is a LoadAction: the tile host calls [onTileRequest] with
 * the button's id, which opens a short session, sets the mode, reads it back and draws the result.
 */
class NoiseTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        scope.future {
            val buds = Buds.get(this@NoiseTileService)
            val clicked = requestParams.currentState.lastClickableId
            val target = if (clicked.startsWith(CLICK_PREFIX)) NoiseState.fromKey(clicked.removePrefix(CLICK_PREFIX)) else null
            var problem: Int? = null
            if (target != null) {
                problem = when {
                    !buds.hasPermission() -> R.string.tile_open_app
                    withTimeoutOrNull(SET_TIMEOUT_MS) { buds.session { setMode(target) } } != true ->
                        R.string.tile_unreachable
                    else -> buds.state.value.notice?.labelRes()
                }
            }
            buds.state.value.let { tile(it.noise, it.levels, problem) }
        }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())

    private fun tile(noise: NoiseState?, levels: List<AncLevel>, problem: Int?): TileBuilders.Tile {
        val openApp = Clickable.Builder()
            .setId("open")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            .build(),
                    )
                    .build(),
            )
            .build()

        val buttons = Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(modeButton(NoiseMode.OFF, R.string.tile_off, noise))
            .addContent(gap(6f))
            .addContent(modeButton(NoiseMode.ANC, R.string.tile_anc, noise))
            .addContent(gap(6f))
            .addContent(modeButton(NoiseMode.TRANSPARENCY, R.string.tile_transparency, noise))
            .build()

        val column = Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(Modifiers.Builder().setClickable(openApp).build())
            .addContent(text(getString(R.string.tile_label), 13f, MUTED))
            .addContent(text(noise.label(this), 15f, WHITE, bold = true))
            .addContent(Spacer.Builder().setHeight(dp(8f)).build())
            .addContent(buttons)
        if (levels.isNotEmpty()) {
            // Picking a level turns noise cancelling on at it.
            val row = Row.Builder().setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            levels.forEachIndexed { i, level ->
                if (i > 0) row.addContent(gap(4f))
                row.addContent(
                    button(
                        NoiseState(NoiseMode.ANC, level), getString(level.tileLabelRes()), noise?.level == level,
                        LEVEL_WIDTH, LEVEL_HEIGHT, 11f,
                    ),
                )
            }
            column.addContent(Spacer.Builder().setHeight(dp(6f)).build())
            column.addContent(row.build())
        }
        if (problem != null) {
            column.addContent(Spacer.Builder().setHeight(dp(8f)).build())
            column.addContent(text(getString(problem), 12f, WARNING))
        }

        val root = Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(column.build())
            .build()

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
            .build()
    }

    private fun modeButton(mode: NoiseMode, label: Int, noise: NoiseState?) =
        button(NoiseState(mode), getString(label), noise?.mode == mode, BUTTON_SIZE, BUTTON_SIZE, 13f)

    private fun button(
        target: NoiseState,
        label: String,
        selected: Boolean,
        width: Float,
        height: Float,
        textSize: Float,
    ): LayoutElementBuilders.LayoutElement {
        val click = Clickable.Builder()
            .setId(CLICK_PREFIX + target.key)
            .setOnClick(ActionBuilders.LoadAction.Builder().build())
            .build()
        return Box.Builder()
            .setWidth(dp(width))
            .setHeight(dp(height))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                Modifiers.Builder()
                    .setClickable(click)
                    .setBackground(
                        Background.Builder()
                            .setColor(argb(if (selected) ACCENT else SURFACE))
                            .setCorner(Corner.Builder().setRadius(dp(height / 2)).build())
                            .build(),
                    )
                    .build(),
            )
            .addContent(text(label, textSize, if (selected) ON_ACCENT else WHITE, bold = selected))
            .build()
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        Text.Builder()
            .setText(value)
            .setMaxLines(2)
            .setFontStyle(
                FontStyle.Builder()
                    .setSize(sp(size))
                    .setColor(argb(color))
                    .setWeight(
                        if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL,
                    )
                    .build(),
            )
            .build()

    private fun gap(width: Float) = Spacer.Builder().setWidth(dp(width)).build()

    private companion object {
        const val RESOURCES_VERSION = "1"
        const val CLICK_PREFIX = "set:"
        const val SET_TIMEOUT_MS = 25_000L
        const val BUTTON_SIZE = 48f
        const val LEVEL_WIDTH = 42f
        const val LEVEL_HEIGHT = 30f
        const val WHITE = 0xFFFFFFFF.toInt()
        const val MUTED = 0xFFB0B0B8.toInt()
        const val WARNING = 0xFFFFB74D.toInt()
        const val SURFACE = 0xFF2A2A33.toInt()
        const val ACCENT = 0xFFA8C7FA.toInt()
        const val ON_ACCENT = 0xFF062E6F.toInt()
    }
}

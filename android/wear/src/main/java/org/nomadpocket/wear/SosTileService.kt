package org.nomadpocket.wear

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture

/**
 * A tile with one big red SOS button that opens the SOS light straight away,
 * so it is one swipe and one tap from the watch face. Static: it never updates
 * or fetches anything.
 */
class SosTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        done(
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout()))
                .build()
        )

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        done(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())

    private fun layout(): LayoutElementBuilders.LayoutElement {
        val openSos = ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName(MainActivity.SOS_SHORTCUT)
                    .addKeyToExtraMapping(MainActivity.EXTRA_SCREEN, ActionBuilders.AndroidStringExtra.Builder().setValue("sos").build())
                    .build()
            )
            .build()
        val button = LayoutElementBuilders.Box.Builder()
            .setWidth(dp(BUTTON_DP))
            .setHeight(dp(BUTTON_DP))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(ModifiersBuilders.Clickable.Builder().setId("sos").setOnClick(openSos).build())
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(DANGER))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(BUTTON_DP / 2)).build())
                            .build()
                    )
                    .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription("Start the SOS signal light").build())
                    .build()
            )
            .addContent(text("SOS", 32f, 0xFFFFFFFF.toInt(), bold = true))
            .build()
        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .addContent(button)
                    .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(8f)).build())
                    .addContent(text("Signal light", 14f, 0xFFA9ADB3.toInt(), bold = false))
                    .build()
            )
            .build()
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean) =
        LayoutElementBuilders.Text.Builder()
            .setText(value)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(size))
                    .setColor(argb(color))
                    .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                    .build()
            )
            .build()

    private fun <T : Any> done(value: T): ListenableFuture<T> = CallbackToFutureAdapter.getFuture { it.set(value) }

    private companion object {
        const val RESOURCES_VERSION = "1"
        const val BUTTON_DP = 104f
        const val DANGER = 0xFFF24236.toInt()
    }
}

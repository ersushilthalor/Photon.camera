package com.hinnka.mycamera.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import com.styropyr0.prismal.PrismalBackdrop

/**
 * Adapts the camera screen's existing captured backdrop to Prismal's glass API.
 * Sharing the same GraphicsLayer avoids capturing a second copy of the camera UI.
 */
@Composable
fun rememberPrismalBackdrop(source: LiquidGlassBackdrop): PrismalBackdrop =
    remember(source) { LiquidGlassBackdropPrismalAdapter(source) }

private class LiquidGlassBackdropPrismalAdapter(
    private val source: LiquidGlassBackdrop
) : PrismalBackdrop {

    override val isCoordinatesDependent: Boolean = true

    override fun readSamplingState() {
        source.positionInRoot
        source.size
    }

    override fun DrawScope.drawPrismalGlass(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        val targetCoordinates = coordinates ?: return
        val sourcePosition = source.positionInRoot
        if (sourcePosition == Offset.Unspecified) return

        val targetPosition = targetCoordinates.positionInRoot()
        withTransform({
            translate(
                left = sourcePosition.x - targetPosition.x,
                top = sourcePosition.y - targetPosition.y
            )
        }) {
            drawLayer(source.layer)
        }
    }
}

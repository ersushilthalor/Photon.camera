package com.hinnka.mycamera.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.styropyr0.prismal.PrismalBackdrop
import kotlin.math.roundToInt

/**
 * Prismal backdrop backed by the actual final-display frame produced by CameraGLSurfaceView.
 *
 * SurfaceView/GLSurfaceView content is not recorded into a Compose GraphicsLayer, so sampling
 * the normal Compose backdrop leaves the glass with little or no camera detail to refract.
 */
@Stable
class CameraPreviewPrismalBackdrop : PrismalBackdrop {
    private var previewImage by mutableStateOf<ImageBitmap?>(null)
    private var previewBounds by mutableStateOf<Rect?>(null)

    override val isCoordinatesDependent: Boolean = true

    fun update(image: ImageBitmap?, bounds: Rect?) {
        previewImage = image
        previewBounds = bounds
    }

    override fun readSamplingState() {
        previewImage
        previewBounds
    }

    override fun DrawScope.drawPrismalGlass(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit)?
    ) {
        val image = previewImage ?: return
        val sourceBounds = previewBounds ?: return
        val targetPosition = coordinates?.positionInRoot() ?: return
        val width = sourceBounds.width.roundToInt()
        val height = sourceBounds.height.roundToInt()
        if (width <= 0 || height <= 0) return

        drawImage(
            image = image,
            dstOffset = IntOffset(
                x = (sourceBounds.left - targetPosition.x).roundToInt(),
                y = (sourceBounds.top - targetPosition.y).roundToInt()
            ),
            dstSize = IntSize(width, height),
            filterQuality = FilterQuality.Medium
        )
    }
}

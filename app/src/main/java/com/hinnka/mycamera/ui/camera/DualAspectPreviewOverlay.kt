package com.hinnka.mycamera.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinnka.mycamera.video.DualVideoAspectRatio

/** Live crop guide for the independent second video output. */
@Composable
fun DualAspectPreviewOverlay(
    ratio: DualVideoAspectRatio,
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val displayRatio = ratio.displayAspectRatio
        val maxFrameWidth = maxWidth * 0.92f
        val maxFrameHeight = maxHeight * 0.88f
        val frameWidth = minOf(maxFrameWidth, maxFrameHeight * displayRatio)
        val frameHeight = frameWidth / displayRatio

        Canvas(modifier = Modifier.fillMaxSize()) {
            val left = (size.width - frameWidth.toPx()) / 2f
            val top = (size.height - frameHeight.toPx()) / 2f
            val right = left + frameWidth.toPx()
            val bottom = top + frameHeight.toPx()
            val shade = Color.Black.copy(alpha = 0.54f)
            drawRect(shade, topLeft = Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(size.width, top))
            drawRect(shade, topLeft = Offset(0f, bottom), size = androidx.compose.ui.geometry.Size(size.width, size.height - bottom))
            drawRect(shade, topLeft = Offset(0f, top), size = androidx.compose.ui.geometry.Size(left, frameHeight.toPx()))
            drawRect(shade, topLeft = Offset(right, top), size = androidx.compose.ui.geometry.Size(size.width - right, frameHeight.toPx()))
            drawRoundRect(
                color = Color.White.copy(alpha = 0.96f),
                topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size(frameWidth.toPx(), frameHeight.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()),
                style = Stroke(width = 2.dp.toPx())
            )
        }

        Box(
            modifier = Modifier.align(Alignment.Center).width(frameWidth).height(frameHeight),
            contentAlignment = Alignment.TopStart
        ) {
            Text(
                text = (if (isRecording) "● DUAL " else "DUAL ") + ratio.displayName,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(bottomEnd = 8.dp))
                    .padding(horizontal = 7.dp, vertical = 5.dp)
            )
        }
    }
}

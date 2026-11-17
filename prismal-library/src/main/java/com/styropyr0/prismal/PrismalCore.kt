package com.styropyr0.prismal

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.styropyr0.prismal.effects.PrismalEffectsScope

@Stable
open class PrismalBackdrop

fun Modifier.drawPrismalGlass(
    backdrop: PrismalBackdrop,
    shape: () -> Shape = { RectangleShape },
    effects: PrismalEffectsScope.() -> Unit = {},
    onDrawSurface: (DrawScope.() -> Unit)? = null,
): Modifier {
    val scope = PrismalEffectsScope().apply(effects)
    val resolvedShape = shape()
    return this
        .clip(resolvedShape)
        .drawBehind {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.14f),
                        Color.White.copy(alpha = 0.04f)
                    )
                )
            )
            onDrawSurface?.invoke(this)
        }
}

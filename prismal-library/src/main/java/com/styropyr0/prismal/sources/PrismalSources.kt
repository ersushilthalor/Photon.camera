package com.styropyr0.prismal.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.styropyr0.prismal.PrismalBackdrop

@Composable
fun rememberPrismalCanvasSource(onDraw: DrawScope.() -> Unit): PrismalBackdrop {
    return remember { PrismalBackdrop() }
}

@Composable
fun rememberPrismalGlassLayer(): PrismalBackdrop {
    return remember { PrismalBackdrop() }
}

fun Modifier.prismalGlassLayer(backdrop: PrismalBackdrop): Modifier = this

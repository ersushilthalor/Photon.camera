package com.styropyr0.prismal.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.styropyr0.prismal.PrismalBackdrop

val LocalPrismalParentGlassLayer = staticCompositionLocalOf<PrismalBackdrop?> { null }

@Composable
fun PrismalGlassToggle(
    selected: () -> Boolean,
    onSelect: (Boolean) -> Unit,
    backdrop: PrismalBackdrop,
    modifier: Modifier = Modifier,
) {
    val isChecked = selected()
    Switch(
        checked = isChecked,
        onCheckedChange = onSelect,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = Color.White.copy(alpha = 0.42f),
            uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
            uncheckedTrackColor = Color.White.copy(alpha = 0.16f),
            uncheckedBorderColor = Color.White.copy(alpha = 0.25f)
        )
    )
}

@Composable
fun PrismalGlassSlider(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    visibilityThreshold: Float = 0.001f,
    backdrop: PrismalBackdrop,
    modifier: Modifier = Modifier,
) {
    Slider(
        value = value().coerceIn(valueRange.start, valueRange.endInclusive),
        onValueChange = onValueChange,
        valueRange = valueRange,
        modifier = modifier,
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Color.White.copy(alpha = 0.75f),
            inactiveTrackColor = Color.White.copy(alpha = 0.22f)
        )
    )
}

@Composable
fun PrismalGlassButton(
    onClick: () -> Unit,
    backdrop: PrismalBackdrop,
    modifier: Modifier = Modifier,
    height: Dp? = 48.dp,
    shape: () -> Shape = { RoundedCornerShape(16.dp) },
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Center,
    nestedGlassSource: PrismalBackdrop? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val resolvedShape = shape()
    val sizedModifier = if (height != null) {
        modifier.height(height)
    } else {
        modifier.defaultMinSize(minHeight = 48.dp)
    }
    Row(
        modifier = sizedModifier
            .clip(resolvedShape)
            .background(Color.White.copy(alpha = 0.12f))
            .border(BorderStroke(0.75.dp, Color.White.copy(alpha = 0.22f)), resolvedShape)
            .clickable(onClick = onClick)
            .padding(contentPadding),
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

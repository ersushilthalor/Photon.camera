package com.styropyr0.prismal.effects

import androidx.compose.ui.unit.Density

class PrismalEffectsScope {
    var luminance: Float = 0.5f
    var blurRadiusPx: Float = 0f
    var saturation: Float = 1f
}

fun PrismalEffectsScope.applyPrismalGlassEffects(
    density: Density,
    adaptiveLuminance: Boolean = false,
    luminance: Float = 0.5f,
    blurRadiusPx: Float = 0f,
    refractionHeightPx: Float = 0f,
    refractionAmountPx: Float = 0f,
    saturation: Float = 1f,
    depthEffect: Boolean = false,
    chromaticAberration: Float = 0f,
    useVibrancy: Boolean = false,
) {
    this.luminance = luminance
    this.blurRadiusPx = blurRadiusPx
    this.saturation = saturation
}

package com.hinnka.mycamera.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hinnka.mycamera.R
import com.hinnka.mycamera.tracking.SubjectTrackingInputResolution
import com.hinnka.mycamera.tracking.SubjectTrackingSettings
import com.hinnka.mycamera.tracking.SubjectTrackingSpeed
import com.hinnka.mycamera.tracking.SubjectTrackingState
import com.hinnka.mycamera.tracking.SubjectTrackingUiState
import com.hinnka.mycamera.tracking.TrackingRect
import com.hinnka.mycamera.ui.theme.AccentColor

@Composable
internal fun SubjectTrackingHud(
    uiState: SubjectTrackingUiState,
    onOpenSettings: () -> Unit,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = when (uiState.snapshot.state) {
        SubjectTrackingState.IDLE -> stringResource(R.string.ai_subject_tracking_tap_hint)
        SubjectTrackingState.LOCKED -> stringResource(R.string.ai_subject_tracking_locked)
        SubjectTrackingState.OCCLUDED -> stringResource(R.string.ai_subject_tracking_occluded)
        SubjectTrackingState.LOST -> stringResource(R.string.ai_subject_tracking_lost)
    }
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.78f),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = if (uiState.isLoadingModel) {
                    stringResource(R.string.ai_subject_tracking_loading)
                } else if (uiState.modelError != null) {
                    stringResource(R.string.ai_subject_tracking_model_error)
                } else {
                    status
                },
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.ai_subject_tracking_settings), color = Color.White)
            }
            TextButton(onClick = onUnlock) {
                Text(stringResource(R.string.ai_subject_tracking_unlock), color = Color.White)
            }
        }
    }
}

@Composable
internal fun SubjectTrackingBoundingBox(
    rect: TrackingRect,
    state: SubjectTrackingState,
    modifier: Modifier = Modifier,
) {
    val boxColor = when (state) {
        SubjectTrackingState.LOCKED -> Color(0xFF42E875)
        SubjectTrackingState.OCCLUDED -> Color(0xFFFFC857)
        SubjectTrackingState.LOST -> Color(0xFFFF625D)
        SubjectTrackingState.IDLE -> Color.Transparent
    }
    Canvas(modifier = modifier) {
        val left = rect.left.coerceIn(0f, 1f) * size.width
        val top = rect.top.coerceIn(0f, 1f) * size.height
        val right = rect.right.coerceIn(0f, 1f) * size.width
        val bottom = rect.bottom.coerceIn(0f, 1f) * size.height
        val boxWidth = right - left
        val boxHeight = bottom - top
        if (boxWidth <= 0f || boxHeight <= 0f || boxColor == Color.Transparent) return@Canvas

        val boundsTopLeft = Offset(left, top)
        val boundsSize = Size(boxWidth, boxHeight)
        val radius = 10.dp.toPx()
        val stroke = 2.5.dp.toPx()
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.58f),
            topLeft = boundsTopLeft,
            size = boundsSize,
            cornerRadius = CornerRadius(radius),
            style = Stroke(width = stroke + 2.dp.toPx()),
        )
        drawRoundRect(
            color = boxColor,
            topLeft = boundsTopLeft,
            size = boundsSize,
            cornerRadius = CornerRadius(radius),
            style = Stroke(width = stroke),
        )

        val tickX = (boxWidth * 0.16f).coerceAtMost(26.dp.toPx())
        val tickY = (boxHeight * 0.16f).coerceAtMost(26.dp.toPx())
        val tickWidth = stroke * 1.35f
        listOf(
            Offset(left, top) to Offset(left + tickX, top),
            Offset(left, top) to Offset(left, top + tickY),
            Offset(right, top) to Offset(right - tickX, top),
            Offset(right, top) to Offset(right, top + tickY),
            Offset(left, bottom) to Offset(left + tickX, bottom),
            Offset(left, bottom) to Offset(left, bottom - tickY),
            Offset(right, bottom) to Offset(right - tickX, bottom),
            Offset(right, bottom) to Offset(right, bottom - tickY),
        ).forEach { (start, end) ->
            drawLine(color = boxColor, start = start, end = end, strokeWidth = tickWidth)
        }
    }
}

@Composable
internal fun SubjectTrackingSettingsDialog(
    settings: SubjectTrackingSettings,
    onSettingsChange: (SubjectTrackingSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 20.dp, top = 14.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("←", color = Color.White, fontSize = 28.sp)
                    }
                    Text(
                        text = stringResource(R.string.ai_subject_tracking_settings),
                        color = Color.White,
                        fontSize = 25.sp,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    TrackingSectionTitle(R.string.ai_subject_tracking_section_tracking)
                    TrackingSettingsCard {
                        Text(stringResource(R.string.ai_subject_tracking_speed), color = Color.White, style = MaterialTheme.typography.titleLarge)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SubjectTrackingSpeed.entries.forEach { speed ->
                                TrackingChoiceChip(
                                    selected = settings.speed == speed,
                                    label = when (speed) {
                                        SubjectTrackingSpeed.SMOOTH -> stringResource(R.string.ai_subject_tracking_speed_smooth)
                                        SubjectTrackingSpeed.NORMAL -> stringResource(R.string.ai_subject_tracking_speed_normal)
                                        SubjectTrackingSpeed.FAST -> stringResource(R.string.ai_subject_tracking_speed_fast)
                                        SubjectTrackingSpeed.ULTRA -> stringResource(R.string.ai_subject_tracking_speed_ultra)
                                    },
                                    onClick = { onSettingsChange(settings.copy(speed = speed)) },
                                )
                            }
                        }
                        Text(stringResource(R.string.ai_subject_tracking_speed_desc), color = Color(0xFFAAAAAA), style = MaterialTheme.typography.bodyMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.ai_subject_tracking_target_zoom), color = Color.White, style = MaterialTheme.typography.titleMedium)
                            Text("%.1fx".format(settings.targetZoom), color = AccentColor, style = MaterialTheme.typography.titleMedium)
                        }
                        Slider(
                            value = settings.targetZoom,
                            onValueChange = { onSettingsChange(settings.copy(targetZoom = it)) },
                            valueRange = 1f..8f,
                            steps = 6,
                        )
                        Text(stringResource(R.string.ai_subject_tracking_target_zoom_desc), color = Color(0xFFAAAAAA), style = MaterialTheme.typography.bodyMedium)
                    }
                    TrackingSectionTitle(R.string.ai_subject_tracking_section_subject)
                    TrackingSettingsCard {
                        TrackingSwitch(
                            title = stringResource(R.string.ai_subject_tracking_human_eye_assist),
                            description = stringResource(R.string.ai_subject_tracking_human_eye_assist_desc),
                            checked = settings.humanEyeAssist,
                            onCheckedChange = { onSettingsChange(settings.copy(humanEyeAssist = it)) },
                        )
                        TrackingSettingDivider()
                        TrackingSwitch(
                            title = stringResource(R.string.ai_subject_tracking_show_box),
                            description = stringResource(R.string.ai_subject_tracking_show_box_desc),
                            checked = settings.showTrackingBox,
                            onCheckedChange = { onSettingsChange(settings.copy(showTrackingBox = it)) },
                        )
                    }
                    TrackingSectionTitle(R.string.ai_subject_tracking_section_performance)
                    TrackingSettingsCard {
                        Text(stringResource(R.string.ai_subject_tracking_resolution), color = Color.White, style = MaterialTheme.typography.titleLarge)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TrackingChoiceChip(
                                modifier = Modifier.weight(1f),
                                selected = settings.inputResolution == SubjectTrackingInputResolution.P720,
                                label = stringResource(R.string.ai_subject_tracking_720p),
                                onClick = { onSettingsChange(settings.copy(inputResolution = SubjectTrackingInputResolution.P720)) },
                            )
                            TrackingChoiceChip(
                                modifier = Modifier.weight(1f),
                                selected = settings.inputResolution == SubjectTrackingInputResolution.P1080,
                                label = stringResource(R.string.ai_subject_tracking_1080p),
                                onClick = { onSettingsChange(settings.copy(inputResolution = SubjectTrackingInputResolution.P1080)) },
                            )
                        }
                        TrackingSwitch(
                            title = stringResource(R.string.ai_subject_tracking_auto_fallback),
                            description = stringResource(R.string.ai_subject_tracking_auto_fallback_desc),
                            checked = settings.automaticResolutionFallback,
                            onCheckedChange = { onSettingsChange(settings.copy(automaticResolutionFallback = it)) },
                        )
                        TrackingSettingDivider()
                        TrackingSwitch(
                            title = stringResource(R.string.ai_subject_tracking_digital_gimbal),
                            description = stringResource(R.string.ai_subject_tracking_digital_gimbal_desc),
                            checked = settings.digitalGimbal,
                            onCheckedChange = { onSettingsChange(settings.copy(digitalGimbal = it)) },
                        )
                        TrackingSettingDivider()
                        TrackingSwitch(
                            title = stringResource(R.string.ai_subject_tracking_cinematic_pan),
                            description = stringResource(R.string.ai_subject_tracking_cinematic_pan_desc),
                            checked = settings.cinematicAutoPan,
                            onCheckedChange = { onSettingsChange(settings.copy(cinematicAutoPan = it)) },
                        )
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End).padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(stringResource(R.string.done), color = AccentColor, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun TrackingSectionTitle(resourceId: Int) {
    Text(
        text = stringResource(resourceId),
        color = Color(0xFF999999),
        fontSize = 18.sp,
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun TrackingSettingsCard(
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Surface(color = Color(0xFF1B1B1B), shape = RoundedCornerShape(26.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
            content = content,
        )
    }
}

@Composable
private fun TrackingChoiceChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = if (selected) AccentColor else Color(0xFF303030),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(
            text = label,
            color = if (selected) Color.Black else Color.White,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun TrackingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(description, color = Color(0xFFAAAAAA), style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TrackingSettingDivider() {
    Box(
        modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.10f)),
    )
}

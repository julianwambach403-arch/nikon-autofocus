package de.nikonautofocus.app.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.nikonautofocus.app.UiState
import de.nikonautofocus.app.ui.theme.Accent
import de.nikonautofocus.app.ui.theme.BlurRed
import de.nikonautofocus.app.ui.theme.Muted
import de.nikonautofocus.app.ui.theme.Panel
import de.nikonautofocus.app.ui.theme.PanelHigh
import de.nikonautofocus.app.ui.theme.SharpGreen
import de.nikonautofocus.app.usb.CameraPropertyState

@Composable
fun RuleOfThirdsGrid(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val color = Color.White.copy(alpha = 0.35f)
        val stroke = 1.4f
        for (i in 1..2) {
            val x = size.width * i / 3f
            val y = size.height * i / 3f
            drawLine(color, Offset(x, 0f), Offset(x, size.height), stroke)
            drawLine(color, Offset(0f, y), Offset(size.width, y), stroke)
        }
    }
}

@Composable
fun HistogramOverlay(bins: List<Int>, modifier: Modifier = Modifier) {
    if (bins.isEmpty()) return
    Canvas(
        modifier
            .width(120.dp)
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(4.dp)
    ) {
        val peak = bins.max().coerceAtLeast(1)
        val barWidth = size.width / bins.size
        val path = Path()
        bins.forEachIndexed { index, count ->
            val x = index * barWidth
            val h = (count.toFloat() / peak) * size.height
            if (index == 0) path.moveTo(x, size.height - h) else path.lineTo(x, size.height - h)
        }
        path.lineTo(size.width, size.height)
        path.lineTo(0f, size.height)
        path.close()
        drawPath(path, Color.White.copy(alpha = 0.55f))
        val highlightStart = bins.size * 7 / 8
        var clipped = false
        for (i in highlightStart until bins.size) if (bins[i] > peak * 0.15f) clipped = true
        if (clipped) {
            drawRect(
                color = BlurRed.copy(alpha = 0.35f),
                topLeft = Offset(size.width * 0.85f, 0f),
                size = Size(size.width * 0.15f, size.height)
            )
        }
    }
}

@Composable
fun LiveViewStatusHud(state: UiState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        state.batteryPercent?.let { percent ->
            BatteryIcon(percent)
            Text(
                "$percent%",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace
            )
        }
        state.remainingImages?.let { shots ->
            Text(
                "$shots",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun BatteryIcon(percent: Int) {
    val fill = when {
        percent <= 15 -> BlurRed
        percent <= 30 -> Color(0xFFFBBF24)
        else -> SharpGreen
    }
    Canvas(Modifier.size(18.dp, 10.dp)) {
        val body = Size(size.width - 3f, size.height)
        drawRect(Color.White, size = body, style = Stroke(width = 1.5f))
        drawRect(Color.White, topLeft = Offset(body.width, size.height * 0.25f), size = Size(3f, size.height * 0.5f))
        val inner = ((percent / 100f) * (body.width - 3f)).coerceAtLeast(0f)
        if (inner > 0f) {
            drawRect(fill, topLeft = Offset(1.5f, 1.5f), size = Size(inner, body.height - 3f))
        }
    }
}

@Composable
fun CaptureRail(
    state: UiState,
    onCapturePhoto: () -> Unit,
    onToggleRecording: () -> Unit,
    onFocusNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val connected = state.connected && state.appControlsCamera
    val canShoot = connected && state.captureAvailable && !state.captureBusy && !state.recording
    val canRecord = connected && state.movieAvailable && !state.captureBusy
    val canFocus = connected && state.focus.autofocusDisabledReason == null

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RoundHudButton(
            label = "AF",
            enabled = canFocus,
            fill = PanelHigh,
            content = Accent,
            onClick = onFocusNow
        )
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(if (canShoot) Color.White else Color.White.copy(alpha = 0.35f))
                .clickable(enabled = canShoot, onClick = onCapturePhoto),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(Color.Transparent)
                    .then(Modifier)
            )
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    color = Color.White,
                    radius = size.minDimension / 2f
                )
                drawCircle(
                    color = Color.Black.copy(alpha = 0.15f),
                    radius = size.minDimension / 2f - 3f,
                    style = Stroke(width = 4f)
                )
                drawCircle(
                    color = Accent,
                    radius = size.minDimension / 2f - 10f
                )
            }
        }
        RoundHudButton(
            label = if (state.recording) "STOP" else "REC",
            enabled = canRecord,
            fill = if (state.recording) BlurRed else PanelHigh,
            content = if (state.recording) Color.Black else BlurRed,
            onClick = onToggleRecording
        )
    }
}

@Composable
fun CompactMonitoringButton(
    monitoring: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fill = if (monitoring) BlurRed else SharpGreen
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(fill.copy(alpha = if (enabled) 0.92f else 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
        )
        Text(
            text = if (monitoring) "STOPP" else "START",
            color = Color.Black,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
    }
}

@Composable
fun FullscreenHudButton(
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(18.dp)) {
            val s = size.minDimension
            val stroke = 2.2f
            val arm = s * 0.36f
            val color = Color.White
            fun corner(cx: Float, cy: Float, dirX: Float, dirY: Float) {
                drawLine(color, Offset(cx, cy), Offset(cx + dirX * arm, cy), stroke, StrokeCap.Round)
                drawLine(color, Offset(cx, cy), Offset(cx, cy + dirY * arm), stroke, StrokeCap.Round)
            }
            if (expanded) {
                corner(arm, arm, -1f, -1f)
                corner(s - arm, arm, 1f, -1f)
                corner(arm, s - arm, -1f, 1f)
                corner(s - arm, s - arm, 1f, 1f)
            } else {
                corner(0f, 0f, 1f, 1f)
                corner(s, 0f, -1f, 1f)
                corner(0f, s, 1f, -1f)
                corner(s, s, -1f, -1f)
            }
        }
    }
}

@Composable
private fun RoundHudButton(
    label: String,
    enabled: Boolean,
    fill: Color,
    content: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(fill.copy(alpha = if (enabled) 0.92f else 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = content,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExposureStrip(
    properties: List<CameraPropertyState>,
    enabled: Boolean,
    busy: Boolean,
    onSelect: (CameraPropertyState, Long) -> Unit
) {
    if (properties.isEmpty()) return
    var editing by remember { mutableStateOf<CameraPropertyState?>(null) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        properties.forEach { property ->
            val writable = enabled && property.writable && !busy
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (writable) PanelHigh else PanelHigh.copy(alpha = 0.55f))
                    .clickable(enabled = writable) { editing = property }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        property.title.substringBefore(' '),
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                        fontSize = 9.sp
                    )
                    Text(
                        property.chipLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (writable) Accent else Muted,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }

    val current = editing
    if (current != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { editing = null },
            sheetState = sheetState,
            containerColor = Panel
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    current.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(12.dp))
                current.options.forEach { option ->
                    val selected = option.value == current.current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Accent.copy(alpha = 0.2f) else Color.Transparent)
                            .clickable {
                                onSelect(current, option.value)
                                editing = null
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            option.label,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                        if (selected) Text("aktiv", color = Accent, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

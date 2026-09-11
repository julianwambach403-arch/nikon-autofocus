package de.nikonautofocus.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import de.nikonautofocus.app.AfFrameOverlay
import de.nikonautofocus.app.FocusTargetSource
import de.nikonautofocus.app.UiState
import de.nikonautofocus.app.focus.FocusSettings
import de.nikonautofocus.app.focus.FocusState
import de.nikonautofocus.app.ui.theme.Accent
import de.nikonautofocus.app.ui.theme.BlurRed
import de.nikonautofocus.app.ui.theme.Muted
import de.nikonautofocus.app.ui.theme.Panel
import de.nikonautofocus.app.ui.theme.PanelHigh
import de.nikonautofocus.app.ui.theme.SharpGreen
import de.nikonautofocus.app.ui.theme.WarnAmber
import de.nikonautofocus.app.usb.FrameSource
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: UiState,
    settings: FocusSettings,
    preview: ImageBitmap?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onToggleMonitoring: () -> Unit,
    onFocusNow: () -> Unit,
    onCapturePhoto: () -> Unit,
    onToggleRecording: () -> Unit,
    onSetAppControl: (Boolean) -> Unit,
    onSetAfAreaMode: (Long) -> Unit,
    onSetAfServoMode: (Long) -> Unit,
    onTapFocusPoint: (Float, Float) -> Unit,
    onSetFieldEnabled: (Boolean) -> Unit,
    onSetFieldSize: (Float) -> Unit,
    onSetCameraProperty: (Int, Long, Int) -> Unit,
    onRefreshDevices: () -> Unit,
    onDismissMessages: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var liveViewFullscreen by remember { mutableStateOf(false) }
    if (liveViewFullscreen) {
        BackHandler { liveViewFullscreen = false }
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Nikon AutoFocus", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = stateLabel(state),
                            style = MaterialTheme.typography.labelSmall,
                            color = Muted
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefreshDevices) {
                        Icon(Icons.Filled.Refresh, contentDescription = "USB erneut suchen")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Panel,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = Accent
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MessageBanners(state, onDismissMessages)
            ConnectionCard(state, onConnect, onDisconnect)
            PreviewCard(
                state = state,
                settings = settings,
                preview = preview,
                onTapFocusPoint = onTapFocusPoint,
                onCapturePhoto = onCapturePhoto,
                onToggleRecording = onToggleRecording,
                onFocusNow = onFocusNow,
                onToggleMonitoring = onToggleMonitoring,
                onEnterFullscreen = { liveViewFullscreen = true }
            )
            if (settings.showExposureControls && state.connected) {
                ExposureStrip(
                    properties = state.cameraControls,
                    enabled = state.appControlsCamera && !state.recording,
                    busy = state.cameraControlBusy,
                    onSelect = { property, value ->
                        onSetCameraProperty(property.propertyCode, value, property.dataType)
                    }
                )
            }
            VerdictRow(state)
            AfModeCard(
                state, settings, onSetAfAreaMode, onSetAfServoMode,
                onSetFieldEnabled, onSetFieldSize
            )
            CaptureCard(state, onCapturePhoto, onToggleRecording, onSetAppControl)
            MetricsCard(state, settings)
            ControlsCard(state, onToggleMonitoring, onFocusNow)
            DiagnosticsCard(state)
            Spacer(Modifier.height(16.dp))
        }
    }

    if (liveViewFullscreen) {
        // Overlay in the activity window, not a Dialog: Compose Dialogs size to their
        // child and place it at (0, 0), so a 3:2 LiveView sat on the left of the screen.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .systemBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            LiveViewStage(
                state = state,
                settings = settings,
                preview = preview,
                isFullscreen = true,
                onTapFocusPoint = onTapFocusPoint,
                onCapturePhoto = onCapturePhoto,
                onToggleRecording = onToggleRecording,
                onFocusNow = onFocusNow,
                onToggleMonitoring = onToggleMonitoring,
                onToggleFullscreen = { liveViewFullscreen = false },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
    }
}

// --------------------------------------------------------------------------- banners

@Composable
private fun MessageBanners(state: UiState, onDismiss: () -> Unit) {
    state.errorMessage?.let { Banner(it, BlurRed, onDismiss) }
    state.warningMessage?.let { Banner(it, WarnAmber, onDismiss) }
}

@Composable
private fun Banner(text: String, accent: Color, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.14f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                color = accent,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Meldung schliessen",
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// --------------------------------------------------------------------------- connection

@Composable
private fun ConnectionCard(
    state: UiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                color = when {
                    state.connected -> SharpGreen
                    state.deviceDetected -> WarnAmber
                    else -> BlurRed
                }
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        state.connected -> state.cameraModel ?: "Kamera verbunden"
                        state.deviceDetected -> "Kamera erkannt, nicht verbunden"
                        else -> "Keine Kamera am USB-Port"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = when {
                        state.connected ->
                            "Firmware ${state.cameraFirmware ?: "?"}  |  Quelle " +
                                frameSourceLabel(state.frameSource)

                        else -> state.deviceDescription ?: "USB-OTG-Adapter und Kamera pruefen"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted
                )
            }
            Spacer(Modifier.width(8.dp))
            if (state.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = Accent
                )
            } else if (state.connected) {
                OutlinedButton(onClick = onDisconnect) { Text("Trennen") }
            } else {
                Button(onClick = onConnect) { Text("Verbinden") }
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color)
    )
}

// --------------------------------------------------------------------------- preview

/**
 * Where the bitmap actually lands inside the preview box.
 *
 * The image is drawn with ContentScale.Fit and centred, so it is letterboxed whenever its
 * aspect ratio differs from the box. The AF frame overlay and the tap handling have to use
 * exactly that rectangle - drawing the frame against the full box would put it in the
 * wrong place on every camera whose LiveView is not 3:2.
 */
private fun fittedImageRect(
    boxWidth: Float,
    boxHeight: Float,
    imageWidth: Int,
    imageHeight: Int
): FloatArray {
    if (imageWidth <= 0 || imageHeight <= 0 || boxWidth <= 0f || boxHeight <= 0f) {
        return floatArrayOf(0f, 0f, boxWidth, boxHeight)
    }
    val imageAspect = imageWidth.toFloat() / imageHeight
    val boxAspect = boxWidth / boxHeight
    val drawWidth: Float
    val drawHeight: Float
    if (imageAspect > boxAspect) {
        drawWidth = boxWidth
        drawHeight = boxWidth / imageAspect
    } else {
        drawHeight = boxHeight
        drawWidth = boxHeight * imageAspect
    }
    return floatArrayOf((boxWidth - drawWidth) / 2f, (boxHeight - drawHeight) / 2f, drawWidth, drawHeight)
}

/**
 * Draws one focus frame into the fitted image rectangle.
 *
 * @param rect    output of [fittedImageRect]: left, top, width, height
 * @param corners true draws camera style corner ticks (used for the frame the camera
 *                reports), false draws a plain box (used for the user placed field), so the
 *                two are told apart at a glance even before reading the legend.
 */
private fun DrawScope.drawFocusFrame(
    rect: FloatArray,
    frame: AfFrameOverlay,
    color: Color,
    corners: Boolean
) {
    val left = rect[0]
    val top = rect[1]
    val drawWidth = rect[2]
    val drawHeight = rect[3]

    val boxWidth = (frame.width * drawWidth).coerceAtLeast(16f)
    val boxHeight = (frame.height * drawHeight).coerceAtLeast(16f)
    val topLeft = Offset(
        left + frame.centerX * drawWidth - boxWidth / 2f,
        top + frame.centerY * drawHeight - boxHeight / 2f
    )

    // Dark halo first so the frame stays readable on bright subjects.
    drawRect(
        color = Color.Black.copy(alpha = 0.55f),
        topLeft = Offset(topLeft.x - 1.5f, topLeft.y - 1.5f),
        size = Size(boxWidth + 3f, boxHeight + 3f),
        style = Stroke(width = 5f)
    )
    drawRect(
        color = color,
        topLeft = topLeft,
        size = Size(boxWidth, boxHeight),
        style = Stroke(width = 3f)
    )

    if (!corners) return

    val tick = minOf(boxWidth, boxHeight) * 0.22f
    val cornerPoints = listOf(
        topLeft.x to topLeft.y,
        topLeft.x + boxWidth to topLeft.y,
        topLeft.x to topLeft.y + boxHeight,
        topLeft.x + boxWidth to topLeft.y + boxHeight
    )
    cornerPoints.forEachIndexed { index, (cornerX, cornerY) ->
        val dx = if (index % 2 == 0) tick else -tick
        val dy = if (index < 2) tick else -tick
        drawLine(
            color = color,
            start = Offset(cornerX, cornerY),
            end = Offset(cornerX + dx, cornerY),
            strokeWidth = 5f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(cornerX, cornerY),
            end = Offset(cornerX, cornerY + dy),
            strokeWidth = 5f,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun PreviewCard(
    state: UiState,
    settings: FocusSettings,
    preview: ImageBitmap?,
    onTapFocusPoint: (Float, Float) -> Unit,
    onCapturePhoto: () -> Unit,
    onToggleRecording: () -> Unit,
    onFocusNow: () -> Unit,
    onToggleMonitoring: () -> Unit,
    onEnterFullscreen: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Black),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 2f)
                .clip(RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            LiveViewStage(
                state = state,
                settings = settings,
                preview = preview,
                isFullscreen = false,
                onTapFocusPoint = onTapFocusPoint,
                onCapturePhoto = onCapturePhoto,
                onToggleRecording = onToggleRecording,
                onFocusNow = onFocusNow,
                onToggleMonitoring = onToggleMonitoring,
                onToggleFullscreen = onEnterFullscreen
            )
        }
    }
}

@Composable
private fun LiveViewStage(
    state: UiState,
    settings: FocusSettings,
    preview: ImageBitmap?,
    isFullscreen: Boolean,
    onTapFocusPoint: (Float, Float) -> Unit,
    onCapturePhoto: () -> Unit,
    onToggleRecording: () -> Unit,
    onFocusNow: () -> Unit,
    onToggleMonitoring: () -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    var zoomScale by remember { mutableStateOf(1f) }
    var panX by remember { mutableStateOf(0f) }
    var panY by remember { mutableStateOf(0f) }
    val canMonitor = state.connected && state.focus.autofocusDisabledReason == null

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (preview != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoomScale
                        scaleY = zoomScale
                        translationX = panX
                        translationY = panY
                    }
                    .pointerInput(preview, state.canTapPreview) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val next = (zoomScale * zoom).coerceIn(1f, 5f)
                            zoomScale = next
                            if (next <= 1.01f) {
                                panX = 0f
                                panY = 0f
                            } else {
                                panX += pan.x
                                panY += pan.y
                            }
                        }
                    }
                    .then(
                        if (state.canTapPreview) {
                            Modifier.pointerInput(preview, state.canTapPreview, zoomScale, panX, panY) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        zoomScale = 1f
                                        panX = 0f
                                        panY = 0f
                                    },
                                    onTap = { offset ->
                                        val unzoomedX = size.width / 2f +
                                            (offset.x - size.width / 2f - panX) / zoomScale
                                        val unzoomedY = size.height / 2f +
                                            (offset.y - size.height / 2f - panY) / zoomScale
                                        val rect = fittedImageRect(
                                            size.width.toFloat(),
                                            size.height.toFloat(),
                                            preview.width,
                                            preview.height
                                        )
                                        val fx = (unzoomedX - rect[0]) / rect[2]
                                        val fy = (unzoomedY - rect[1]) / rect[3]
                                        if (fx in 0f..1f && fy in 0f..1f) onTapFocusPoint(fx, fy)
                                    }
                                )
                            }
                        } else {
                            Modifier
                        }
                    )
            ) {
                    Image(
                        bitmap = preview,
                        contentDescription = "LiveView",
                        alignment = Alignment.Center,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )

                if (settings.showGrid) {
                    RuleOfThirdsGrid(Modifier.fillMaxSize())
                }

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val rect = fittedImageRect(
                        size.width, size.height, preview.width, preview.height
                    )
                    state.manualField?.let { field ->
                        drawFocusFrame(rect, field, Accent, corners = false)
                    }
                    state.afFrame?.let { frame ->
                        drawFocusFrame(
                            rect,
                            frame,
                            if (frame.focused) SharpGreen else WarnAmber,
                            corners = true
                        )
                    }
                }
            }
        } else {
            Text(
                text = when {
                    state.connected && !state.appControlsCamera ->
                        "LiveView aus - die Kamera wird gerade am Body bedient"

                    state.connected -> "Warte auf LiveView-Bild ..."
                    else -> "Kein LiveView"
                },
                color = Muted,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            if (state.recording) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(BlurRed.copy(alpha = 0.92f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color.Black)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "REC " + formatDuration(state.recordingElapsedMs),
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            FullscreenHudButton(
                expanded = isFullscreen,
                onClick = onToggleFullscreen
            )
        }

        if (state.captureFlashActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Accent)
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "FOTO AUSGELOEST",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        if (state.autofocusFlashActive ||
            state.focus.state == FocusState.AUTOFOCUS_TRIGGERED
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(4.dp, Accent, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Accent)
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "AUTOFOCUS AKTIVIERT",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }

        if (preview != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LiveViewStatusHud(state)
                if (settings.showHistogram) {
                    HistogramOverlay(state.histogram)
                }
                if (zoomScale > 1.01f) {
                    Text(
                        text = "%.1fx  ·  DoppelTipp setzt zurueck".format(zoomScale),
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.45f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            if (state.connected && state.appControlsCamera) {
                CaptureRail(
                    state = state,
                    onCapturePhoto = onCapturePhoto,
                    onToggleRecording = onToggleRecording,
                    onFocusNow = onFocusNow,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(8.dp)
                )
            }

            Text(
                text = "%.1f fps  |  %s  |  %d ms  |  %d kB".format(
                    state.fps,
                    state.analysisResolution,
                    state.analysisDurationMs,
                    state.jpegSizeKb
                ),
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
            )
        }

        CompactMonitoringButton(
            monitoring = state.focus.monitoring,
            enabled = canMonitor,
            onClick = onToggleMonitoring,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
        )
    }
}

// --------------------------------------------------------------------------- verdict

@Composable
private fun VerdictRow(state: UiState) {
    val focus = state.focus
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BigValueCard(
            label = "Schaerfewert (roh)",
            value = formatScore(focus.rawScore),
            accent = if (focus.rawScore < focus.threshold) BlurRed else SharpGreen,
            modifier = Modifier.weight(1f)
        )
        BigValueCard(
            label = "Geglaettet (N=${focus.windowSize})",
            value = if (focus.windowWarm) formatScore(focus.smoothedScore) else "--",
            accent = if (focus.isSharp) SharpGreen else BlurRed,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun BigValueCard(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                color = accent,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

// --------------------------------------------------------------------------- af modes

/** Names the source of the focus information, so the frames are never ambiguous. */
@Composable
private fun FocusTargetLegend(state: UiState) {
    val (color, title, explanation) = when (state.focusTargetSource) {
        FocusTargetSource.CAMERA_REPORTED -> Triple(
            SharpGreen,
            "Fokus auf: AF-Feld der Kamera",
            "Der gruene Rahmen mit den Ecken ist das Messfeld, das die Kamera selbst " +
                "meldet - genau darauf stellt sie scharf."
        )

        FocusTargetSource.MANUAL_FIELD -> Triple(
            Accent,
            "Fokus auf: eigenes Fokusfeld",
            "Diese Kamera meldet kein AF-Feld im LiveView-Datenstrom. Der blaue Rahmen " +
                "ist das Feld, in dem die App die Schaerfe misst - und das sie der Kamera " +
                "als AF-Messfeld schickt, sofern der Body das unterstuetzt."
        )

        FocusTargetSource.WHOLE_FRAME -> Triple(
            WarnAmber,
            "Fokus auf: ganzes Bild",
            "Die Kamera meldet kein AF-Feld, und es ist keines gesetzt. Die Schaerfe wird " +
                "ueber das komplette Bild gemittelt - ein unruhiger Hintergrund zaehlt " +
                "dabei genauso viel wie das Motiv. Schalte unten ein Fokusfeld ein."
        )

        FocusTargetSource.NONE -> Triple(
            Muted,
            "Fokus: keine Anzeige",
            "Ohne laufenden LiveView gibt es nichts anzuzeigen."
        )
    }

    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = color
            )
            Text(explanation, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

@Composable
private fun ManualFieldControls(
    state: UiState,
    settings: FocusSettings,
    onSetFieldEnabled: (Boolean) -> Unit,
    onSetFieldSize: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "Eigenes Fokusfeld",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                "Schaerfe nur im Feld messen. Tippe ins Vorschaubild, um es zu setzen. " +
                    "ACHTUNG: aendert die Skala des Schaerfewerts, Schwellwert neu einstellen.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = settings.manualFieldEnabled,
            onCheckedChange = onSetFieldEnabled,
            enabled = state.connected
        )
    }

    if (settings.manualFieldEnabled) {
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Feldgroesse", style = MaterialTheme.typography.labelMedium, color = Muted)
            Text(
                "%d %%".format((settings.manualFieldSize * 100).roundToInt()),
                style = MaterialTheme.typography.labelMedium,
                color = Accent,
                fontFamily = FontFamily.Monospace
            )
        }
        Slider(
            value = settings.manualFieldSize,
            onValueChange = onSetFieldSize,
            valueRange = FocusSettings.FIELD_SIZE_MIN..FocusSettings.FIELD_SIZE_MAX
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AfModeCard(
    state: UiState,
    settings: FocusSettings,
    onSetAfAreaMode: (Long) -> Unit,
    onSetAfServoMode: (Long) -> Unit,
    onSetFieldEnabled: (Boolean) -> Unit,
    onSetFieldSize: (Float) -> Unit
) {
    if (!state.connected) return
    val areaMode = state.afAreaMode
    val servoMode = state.afServoMode

    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Fokus",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (state.afFrame != null) {
                Text(
                    text = if (state.afFrame.focused) "Fokus sitzt" else "kein Fokus",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.afFrame.focused) SharpGreen else WarnAmber
                )
            }
        }

        // What the frames on the preview actually mean.
        Spacer(Modifier.height(8.dp))
        FocusTargetLegend(state)

        Spacer(Modifier.height(12.dp))
        ManualFieldControls(state, settings, onSetFieldEnabled, onSetFieldSize)

        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = PanelHigh)
        Spacer(Modifier.height(10.dp))

        if (areaMode == null && servoMode == null) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Diese Kamera meldet keine umschaltbaren LiveView-Fokusmodi " +
                    "(0xD05D / 0xD061 fehlen in der Eigenschaftsliste). Die " +
                    "Messfeldsteuerung muss dann am Kamerabody eingestellt werden.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
            return@SectionCard
        }

        areaMode?.let { mode ->
            Spacer(Modifier.height(10.dp))
            Text(
                "AF-Messfeldsteuerung",
                style = MaterialTheme.typography.labelMedium,
                color = Muted
            )
            Spacer(Modifier.height(6.dp))
            ModeChips(
                options = mode.options.map { it.value to it.label },
                selected = mode.current,
                enabled = mode.writable && !state.afModeBusy && !state.recording,
                onSelect = onSetAfAreaMode
            )
        }

        servoMode?.let { mode ->
            Spacer(Modifier.height(12.dp))
            Text("AF-Betriebsart", style = MaterialTheme.typography.labelMedium, color = Muted)
            Spacer(Modifier.height(6.dp))
            ModeChips(
                options = mode.options.map { it.value to it.label },
                selected = mode.current,
                enabled = mode.writable && !state.afModeBusy && !state.recording,
                onSelect = onSetAfServoMode
            )
        }

        if (state.canMoveAfArea) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Tippe ins Vorschaubild, um das AF-Messfeld dorthin zu verschieben.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
        }
    }
}

/** Simple wrapping row of selectable pills. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeChips(
    options: List<Pair<Long, String>>,
    selected: Long,
    enabled: Boolean,
    onSelect: (Long) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (isSelected) Accent else PanelHigh)
                    .clickable(enabled = enabled && !isSelected) { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = label,
                    color = when {
                        isSelected -> Color.Black
                        enabled -> MaterialTheme.colorScheme.onSurface
                        else -> Muted
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

// --------------------------------------------------------------------------- capture

@Composable
private fun CaptureCard(
    state: UiState,
    onCapturePhoto: () -> Unit,
    onToggleRecording: () -> Unit,
    onSetAppControl: (Boolean) -> Unit
) {
    val connected = state.connected
    val canShoot = connected && state.captureAvailable && state.appControlsCamera &&
        !state.captureBusy && !state.recording
    val canRecord = connected && state.movieAvailable && state.appControlsCamera &&
        !state.captureBusy

    SectionCard {
        Text(
            "Aufnahme",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onCapturePhoto,
                enabled = canShoot,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Color.Black
                )
            ) {
                Text("FOTO", fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onToggleRecording,
                enabled = canRecord,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.recording) BlurRed else PanelHigh,
                    contentColor = if (state.recording) Color.Black else BlurRed
                )
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (state.recording) "VIDEO STOPP" else "VIDEO START",
                        fontWeight = FontWeight.Bold
                    )
                    if (state.recording) {
                        Text(
                            text = formatDuration(state.recordingElapsedMs),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        if (connected && (!state.captureAvailable || !state.movieAvailable)) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = buildString {
                    if (!state.captureAvailable) {
                        append("Fernausloesung: von dieser Kamera nicht angeboten. ")
                    }
                    if (!state.movieAvailable) {
                        append(
                            "Videoaufnahme per USB: von dieser Kamera nicht angeboten " +
                                "(0x920A/0x920B fehlen)."
                        )
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = WarnAmber
            )
        }

        state.lastCaptureInfo?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Muted)
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = PanelHigh)
        Spacer(Modifier.height(10.dp))

        // Control handover. This is the answer to "the buttons on my camera do nothing".
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (state.appControlsCamera) {
                        "App steuert die Kamera"
                    } else {
                        "Kamera-Bedienung freigegeben"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (state.appControlsCamera) {
                        "Solange LiveView per USB laeuft, sperrt Nikon die Tasten an der " +
                            "Kamera. Zum Ausloesen am Body hier freigeben - LiveView und " +
                            "Fokusueberwachung enden dann."
                    } else {
                        "Ausloeser und Video-Taste an der Kamera funktionieren wieder. " +
                            "LiveView und Fokusueberwachung sind pausiert."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted
                )
            }
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = state.appControlsCamera,
                onCheckedChange = { onSetAppControl(it) },
                enabled = connected && !state.recording && !state.captureBusy
            )
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

// --------------------------------------------------------------------------- metrics

@Composable
private fun MetricsCard(state: UiState, settings: FocusSettings) {
    val focus = state.focus
    SectionCard {
        ScoreChart(
            history = state.scoreHistory,
            threshold = focus.threshold.toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
        )
        Spacer(Modifier.height(12.dp))

        MetricRow("Zustand", focus.state.name)
        MetricRow("Schwellwert", formatScore(focus.threshold))
        MetricRow(
            "Unscharfe Frames",
            "${focus.blurryFrames} / ${focus.requiredBlurryFrames}"
        )
        LinearProgressIndicator(
            progress = { focus.blurProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = BlurRed,
            trackColor = PanelHigh
        )
        Spacer(Modifier.height(8.dp))

        MetricRow(
            "Mittelwertfenster",
            "${focus.samplesInWindow} / ${focus.windowSize}" +
                if (focus.windowWarm) " (bereit)" else " (fuellt sich)"
        )

        if (focus.state == FocusState.COOLDOWN) {
            MetricRow(
                "Cooldown",
                "%.1f s".format(focus.cooldownRemainingMs / 1000.0)
            )
            LinearProgressIndicator(
                progress = { focus.cooldownProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = Accent,
                trackColor = PanelHigh
            )
        } else {
            MetricRow("Cooldown", "%.1f s (inaktiv)".format(settings.cooldownMs / 1000.0))
        }

        MetricRow("Autofokus ausgeloest", focus.autofocusCount.toString())
        focus.lastAutofocusResult?.let { MetricRow("Letztes Ergebnis", it) }
        if (state.droppedFrames > 0) {
            MetricRow("Verworfene Frames", state.droppedFrames.toString())
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Muted)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

/** Sparkline of the recent raw scores with the threshold drawn across it. */
@Composable
private fun ScoreChart(history: List<Float>, threshold: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (history.isEmpty()) return@Canvas

        val maxValue = max(history.max(), threshold * 1.6f).coerceAtLeast(1f)
        val stepX = if (history.size > 1) w / (history.size - 1) else w

        // threshold line
        val thresholdY = h - (threshold / maxValue) * h
        drawLine(
            color = WarnAmber.copy(alpha = 0.7f),
            start = androidx.compose.ui.geometry.Offset(0f, thresholdY),
            end = androidx.compose.ui.geometry.Offset(w, thresholdY),
            strokeWidth = 2f
        )

        var previousX = 0f
        var previousY = h - (history[0] / maxValue) * h
        for (i in 1 until history.size) {
            val x = i * stepX
            val y = h - (history[i] / maxValue) * h
            drawLine(
                color = if (history[i] < threshold) BlurRed else SharpGreen,
                start = androidx.compose.ui.geometry.Offset(previousX, previousY),
                end = androidx.compose.ui.geometry.Offset(x, y),
                strokeWidth = 3f,
                cap = StrokeCap.Round
            )
            previousX = x
            previousY = y
        }

        drawRect(
            color = PanelHigh,
            style = Stroke(width = 1f)
        )
    }
}

// --------------------------------------------------------------------------- controls

@Composable
private fun ControlsCard(
    state: UiState,
    onToggleMonitoring: () -> Unit,
    onFocusNow: () -> Unit
) {
    val focus = state.focus
    val canOperate = state.connected && focus.autofocusDisabledReason == null

    SectionCard {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onToggleMonitoring,
                enabled = canOperate,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (focus.monitoring) BlurRed else SharpGreen,
                    contentColor = Color.Black
                )
            ) {
                Text(
                    text = if (focus.monitoring) "Ueberwachung STOPP" else "Ueberwachung START",
                    fontWeight = FontWeight.SemiBold
                )
            }
            FilledTonalButton(
                onClick = onFocusNow,
                enabled = canOperate,
                modifier = Modifier.weight(1f)
            ) {
                Text("Jetzt fokussieren")
            }
        }

        focus.autofocusDisabledReason?.let {
            Spacer(Modifier.height(10.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = WarnAmber,
                textAlign = TextAlign.Start
            )
        }

        state.infoMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

// --------------------------------------------------------------------------- diagnostics

@Composable
private fun DiagnosticsCard(state: UiState) {
    val diagnostics = state.diagnostics ?: return
    // Open by itself when there is something the user actually needs to read, such as a
    // refused movie start - otherwise the values would sit behind a tap nobody makes.
    var expanded by remember(diagnostics) {
        mutableStateOf(diagnostics.contains(DIAGNOSTICS_ATTENTION_MARKER))
    }

    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "PTP-Diagnose",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Ausblenden" else "Anzeigen")
            }
        }
        if (expanded) {
            Text(
                text = diagnostics,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = Muted
            )
        }
    }
}

/** Kept in sync with the header MainViewModel writes in front of a movie readout. */
private const val DIAGNOSTICS_ATTENTION_MARKER = "Videostart abgelehnt"

// --------------------------------------------------------------------------- helpers

@Composable
private fun SectionCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

private fun formatScore(value: Double): String = when {
    value >= 10_000 -> value.roundToInt().toString()
    value >= 100 -> "%.0f".format(value)
    else -> "%.1f".format(value)
}

private fun frameSourceLabel(source: FrameSource): String = when (source) {
    FrameSource.LIVE_VIEW -> "LiveView (0x9203)"
    FrameSource.PREVIEW_IMAGE -> "Preview (0x9200)"
    FrameSource.NONE -> "keine"
}

private fun stateLabel(state: UiState): String = when (state.focus.state) {
    FocusState.DISCONNECTED -> "Nicht verbunden"
    FocusState.CONNECTING -> "Verbinde ..."
    FocusState.LIVEVIEW -> "LiveView aktiv"
    FocusState.ANALYZING -> "Analysiere"
    FocusState.BLUR_DETECTED -> "Unschaerfe bestaetigt"
    FocusState.AUTOFOCUS_TRIGGERED -> "Autofokus laeuft"
    FocusState.COOLDOWN -> "Cooldown"
    FocusState.ERROR -> "Fehler"
}

package de.nikonautofocus.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.nikonautofocus.app.focus.FocusSettings
import de.nikonautofocus.app.ui.theme.Accent
import de.nikonautofocus.app.ui.theme.Muted
import de.nikonautofocus.app.ui.theme.Panel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: FocusSettings,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onChange: ((FocusSettings) -> FocusSettings) -> Unit,
    onReset: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Panel
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            Text(
                "Einstellungen",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Der Schaerfewert haengt von der Analyse-Aufloesung und vom Motiv ab. " +
                    "Beobachte den Live-Wert bei scharfem und bei unscharfem Bild und lege " +
                    "den Schwellwert dazwischen.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
            Spacer(Modifier.height(16.dp))

            SettingSlider(
                title = "Schwellwert (THRESHOLD)",
                subtitle = "score < Schwellwert gilt als unscharf",
                value = settings.threshold.toFloat(),
                valueText = "%.0f".format(settings.threshold),
                range = FocusSettings.THRESHOLD_MIN.toFloat()..FocusSettings.THRESHOLD_MAX.toFloat(),
                onValueChange = { newValue ->
                    onChange { it.copy(threshold = newValue.toDouble()) }
                },
                onStep = { delta ->
                    onChange { it.copy(threshold = it.threshold + delta) }
                },
                stepSize = 10.0
            )

            SettingSlider(
                title = "Aufeinanderfolgende unscharfe Frames",
                subtitle = "So oft in Folge muss der geglaettete Wert unter dem Schwellwert " +
                    "liegen, bevor fokussiert wird",
                value = settings.requiredBlurryFrames.toFloat(),
                valueText = settings.requiredBlurryFrames.toString(),
                range = FocusSettings.FRAMES_MIN.toFloat()..FocusSettings.FRAMES_MAX.toFloat(),
                steps = FocusSettings.FRAMES_MAX - FocusSettings.FRAMES_MIN - 1,
                onValueChange = { newValue ->
                    onChange { it.copy(requiredBlurryFrames = newValue.roundToInt()) }
                }
            )

            SettingSlider(
                title = "Groesse des Mittelwertfensters (N)",
                subtitle = "Gleitender Mittelwert ueber die letzten N Messwerte",
                value = settings.movingAverageSize.toFloat(),
                valueText = settings.movingAverageSize.toString(),
                range = FocusSettings.AVERAGE_MIN.toFloat()..FocusSettings.AVERAGE_MAX.toFloat(),
                steps = FocusSettings.AVERAGE_MAX - FocusSettings.AVERAGE_MIN - 1,
                onValueChange = { newValue ->
                    onChange { it.copy(movingAverageSize = newValue.roundToInt()) }
                }
            )

            SettingSlider(
                title = "Cooldown",
                subtitle = "Sperrzeit nach einem Autofokus-Befehl",
                value = settings.cooldownMs / 1000f,
                valueText = "%.1f s".format(settings.cooldownMs / 1000.0),
                range = FocusSettings.COOLDOWN_MIN_MS / 1000f..FocusSettings.COOLDOWN_MAX_MS / 1000f,
                onValueChange = { newValue ->
                    onChange { it.copy(cooldownMs = (newValue * 1000).toLong()) }
                }
            )

            SettingSlider(
                title = "Analyse-Frequenz",
                subtitle = "Obergrenze. Der USB-LiveView-Stream ist meist langsamer",
                value = settings.analysisFps.toFloat(),
                valueText = "${settings.analysisFps} fps",
                range = FocusSettings.FPS_MIN.toFloat()..FocusSettings.FPS_MAX.toFloat(),
                steps = FocusSettings.FPS_MAX - FocusSettings.FPS_MIN - 1,
                onValueChange = { newValue ->
                    onChange { it.copy(analysisFps = newValue.roundToInt()) }
                }
            )

            SettingSlider(
                title = "Analyse-Aufloesung",
                subtitle = "Breite, auf die das Bild vor dem Laplace-Filter skaliert wird. " +
                    "ACHTUNG: aendert die Skala des Schaerfewerts",
                value = settings.analysisWidth.toFloat(),
                valueText = "${settings.analysisWidth} px",
                range = FocusSettings.WIDTH_MIN.toFloat()..FocusSettings.WIDTH_MAX.toFloat(),
                onValueChange = { newValue ->
                    onChange { it.copy(analysisWidth = (newValue.roundToInt() / 20) * 20) }
                }
            )

            SettingSlider(
                title = "Autofokus-Timeout",
                subtitle = "Wie lange auf die Rueckmeldung der Kamera gewartet wird",
                value = settings.autofocusTimeoutMs / 1000f,
                valueText = "%.1f s".format(settings.autofocusTimeoutMs / 1000.0),
                range = (FocusSettings.AF_TIMEOUT_MIN_MS / 1000f)
                    .rangeTo(FocusSettings.AF_TIMEOUT_MAX_MS / 1000f),
                onValueChange = { newValue ->
                    onChange { it.copy(autofocusTimeoutMs = (newValue * 1000).toLong()) }
                }
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text(
                "Aufnahme und Kamerasteuerung",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))

            SettingSwitch(
                title = "Fotos auf die Speicherkarte",
                subtitle = "Aus: Aufnahme landet im internen SDRAM der Kamera und wird beim " +
                    "Ausschalten verworfen",
                checked = settings.captureToCard,
                onCheckedChange = { checked -> onChange { it.copy(captureToCard = checked) } }
            )

            SettingSwitch(
                title = "PC-Steuerung uebernehmen",
                subtitle = "Sendet Nikon_ChangeCameraMode(1) beim LiveView-Start. Das ist der " +
                    "Grund, warum die Tasten an der Kamera gesperrt sind. Ausschalten laesst " +
                    "die Bedienelemente frei - manche Bodies verweigern dann LiveView. " +
                    "Wirkt erst beim naechsten LiveView-Start.",
                checked = settings.takeCameraControl,
                onCheckedChange = { checked -> onChange { it.copy(takeCameraControl = checked) } }
            )

            SettingSwitch(
                title = "Fokusueberwachung waehrend Videoaufnahme pausieren",
                subtitle = "Die D3400 weist Fokusbefehle waehrend einer internen Aufzeichnung " +
                    "ab. Pausieren verhindert sinnlose Versuche.",
                checked = settings.pauseMonitoringWhileRecording,
                onCheckedChange = { checked ->
                    onChange { it.copy(pauseMonitoringWhileRecording = checked) }
                }
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text(
                "LiveView-Anzeige",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))

            SettingSwitch(
                title = "Gitter (Drittelregel)",
                subtitle = "Hilfslinien ueber dem LiveView, wie in Camera Connect & Control",
                checked = settings.showGrid,
                onCheckedChange = { checked -> onChange { it.copy(showGrid = checked) } }
            )

            SettingSwitch(
                title = "Histogramm",
                subtitle = "Helligkeitsverteilung des LiveView-Bildes, aktualisiert mit jedem Frame",
                checked = settings.showHistogram,
                onCheckedChange = { checked -> onChange { it.copy(showHistogram = checked) } }
            )

            SettingSwitch(
                title = "Belichtungsleiste",
                subtitle = "ISO, Zeit, Blende, Korrektur, Weissabgleich und weitere PTP-Werte " +
                    "direkt unter dem LiveView. Nur was die Kamera anbietet, wird angezeigt.",
                checked = settings.showExposureControls,
                onCheckedChange = { checked ->
                    onChange { it.copy(showExposureControls = checked) }
                }
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f)) {
                    Text("Standardwerte")
                }
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Schliessen")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingSlider(
    title: String,
    subtitle: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
    onStep: ((Double) -> Unit)? = null,
    stepSize: Double = 1.0
) {
    Column(modifier = Modifier.padding(bottom = 14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onStep != null) {
                    TextButton(onClick = { onStep(-stepSize) }) { Text("-") }
                }
                Text(
                    valueText,
                    color = Accent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                if (onStep != null) {
                    TextButton(onClick = { onStep(stepSize) }) { Text("+") }
                }
            }
        }
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps
        )
    }
}

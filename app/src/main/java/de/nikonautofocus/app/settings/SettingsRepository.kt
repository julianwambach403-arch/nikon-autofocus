package de.nikonautofocus.app.settings

import android.content.Context
import android.content.SharedPreferences
import de.nikonautofocus.app.focus.FocusSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists [FocusSettings] across app restarts.
 *
 * SharedPreferences is enough here: the settings are a handful of scalars written only when
 * the user moves a slider, so DataStore would add a dependency without adding anything.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<FocusSettings> = _settings.asStateFlow()

    val current: FocusSettings get() = _settings.value

    private fun load(): FocusSettings {
        val defaults = FocusSettings()
        return FocusSettings(
            threshold = prefs.getFloat(KEY_THRESHOLD, defaults.threshold.toFloat()).toDouble(),
            requiredBlurryFrames = prefs.getInt(KEY_FRAMES, defaults.requiredBlurryFrames),
            movingAverageSize = prefs.getInt(KEY_AVERAGE, defaults.movingAverageSize),
            cooldownMs = prefs.getLong(KEY_COOLDOWN, defaults.cooldownMs),
            analysisFps = prefs.getInt(KEY_FPS, defaults.analysisFps),
            analysisWidth = prefs.getInt(KEY_WIDTH, defaults.analysisWidth),
            autofocusTimeoutMs = prefs.getLong(KEY_AF_TIMEOUT, defaults.autofocusTimeoutMs),
            captureToCard = prefs.getBoolean(KEY_CAPTURE_TO_CARD, defaults.captureToCard),
            takeCameraControl = prefs.getBoolean(KEY_TAKE_CONTROL, defaults.takeCameraControl),
            pauseMonitoringWhileRecording = prefs.getBoolean(
                KEY_PAUSE_WHILE_RECORDING, defaults.pauseMonitoringWhileRecording
            ),
            manualFieldEnabled = prefs.getBoolean(KEY_FIELD_ON, defaults.manualFieldEnabled),
            manualFieldX = prefs.getFloat(KEY_FIELD_X, defaults.manualFieldX),
            manualFieldY = prefs.getFloat(KEY_FIELD_Y, defaults.manualFieldY),
            manualFieldSize = prefs.getFloat(KEY_FIELD_SIZE, defaults.manualFieldSize),
            showGrid = prefs.getBoolean(KEY_SHOW_GRID, defaults.showGrid),
            showHistogram = prefs.getBoolean(KEY_SHOW_HISTOGRAM, defaults.showHistogram),
            showExposureControls = prefs.getBoolean(
                KEY_SHOW_EXPOSURE, defaults.showExposureControls
            ),
            intervalHours = prefs.getInt(KEY_INTERVAL_H, defaults.intervalHours),
            intervalMinutes = prefs.getInt(KEY_INTERVAL_M, defaults.intervalMinutes),
            intervalSeconds = prefs.getInt(KEY_INTERVAL_S, defaults.intervalSeconds),
            intervalCount = prefs.getInt(KEY_INTERVAL_COUNT, defaults.intervalCount),
            intervalDelaySeconds = prefs.getInt(KEY_INTERVAL_DELAY, defaults.intervalDelaySeconds),
            bracketingEnabled = prefs.getBoolean(KEY_BRACKET_ON, defaults.bracketingEnabled),
            bracketingCount = prefs.getInt(KEY_BRACKET_COUNT, defaults.bracketingCount),
            bracketingStepThirds = prefs.getInt(KEY_BRACKET_STEP, defaults.bracketingStepThirds),
            bracketingZeroFirst = prefs.getBoolean(KEY_BRACKET_ZERO_FIRST, defaults.bracketingZeroFirst)
        ).sanitized()
    }

    fun update(persist: Boolean = true, transform: (FocusSettings) -> FocusSettings) {
        val next = transform(_settings.value).sanitized()
        _settings.value = next
        if (!persist) return
        prefs.edit()
            .putFloat(KEY_THRESHOLD, next.threshold.toFloat())
            .putInt(KEY_FRAMES, next.requiredBlurryFrames)
            .putInt(KEY_AVERAGE, next.movingAverageSize)
            .putLong(KEY_COOLDOWN, next.cooldownMs)
            .putInt(KEY_FPS, next.analysisFps)
            .putInt(KEY_WIDTH, next.analysisWidth)
            .putLong(KEY_AF_TIMEOUT, next.autofocusTimeoutMs)
            .putBoolean(KEY_CAPTURE_TO_CARD, next.captureToCard)
            .putBoolean(KEY_TAKE_CONTROL, next.takeCameraControl)
            .putBoolean(KEY_PAUSE_WHILE_RECORDING, next.pauseMonitoringWhileRecording)
            .putBoolean(KEY_FIELD_ON, next.manualFieldEnabled)
            .putFloat(KEY_FIELD_X, next.manualFieldX)
            .putFloat(KEY_FIELD_Y, next.manualFieldY)
            .putFloat(KEY_FIELD_SIZE, next.manualFieldSize)
            .putBoolean(KEY_SHOW_GRID, next.showGrid)
            .putBoolean(KEY_SHOW_HISTOGRAM, next.showHistogram)
            .putBoolean(KEY_SHOW_EXPOSURE, next.showExposureControls)
            .putInt(KEY_INTERVAL_H, next.intervalHours)
            .putInt(KEY_INTERVAL_M, next.intervalMinutes)
            .putInt(KEY_INTERVAL_S, next.intervalSeconds)
            .putInt(KEY_INTERVAL_COUNT, next.intervalCount)
            .putInt(KEY_INTERVAL_DELAY, next.intervalDelaySeconds)
            .putBoolean(KEY_BRACKET_ON, next.bracketingEnabled)
            .putInt(KEY_BRACKET_COUNT, next.bracketingCount)
            .putInt(KEY_BRACKET_STEP, next.bracketingStepThirds)
            .putBoolean(KEY_BRACKET_ZERO_FIRST, next.bracketingZeroFirst)
            .apply()
    }

    fun resetToDefaults() = update { _ -> FocusSettings() }

    companion object {
        private const val PREFS_NAME = "focus_settings"
        private const val KEY_THRESHOLD = "threshold"
        private const val KEY_FRAMES = "required_blurry_frames"
        private const val KEY_AVERAGE = "moving_average_size"
        private const val KEY_COOLDOWN = "cooldown_ms"
        private const val KEY_FPS = "analysis_fps"
        private const val KEY_WIDTH = "analysis_width"
        private const val KEY_AF_TIMEOUT = "af_timeout_ms"
        private const val KEY_CAPTURE_TO_CARD = "capture_to_card"
        private const val KEY_TAKE_CONTROL = "take_camera_control"
        private const val KEY_PAUSE_WHILE_RECORDING = "pause_while_recording"
        private const val KEY_FIELD_ON = "manual_field_enabled"
        private const val KEY_FIELD_X = "manual_field_x"
        private const val KEY_FIELD_Y = "manual_field_y"
        private const val KEY_FIELD_SIZE = "manual_field_size"
        private const val KEY_SHOW_GRID = "show_grid"
        private const val KEY_SHOW_HISTOGRAM = "show_histogram"
        private const val KEY_SHOW_EXPOSURE = "show_exposure_controls"
        private const val KEY_INTERVAL_H = "interval_hours"
        private const val KEY_INTERVAL_M = "interval_minutes"
        private const val KEY_INTERVAL_S = "interval_seconds"
        private const val KEY_INTERVAL_COUNT = "interval_count"
        private const val KEY_INTERVAL_DELAY = "interval_delay_sec"
        private const val KEY_BRACKET_ON = "bracketing_enabled"
        private const val KEY_BRACKET_COUNT = "bracketing_count"
        private const val KEY_BRACKET_STEP = "bracketing_step_thirds"
        private const val KEY_BRACKET_ZERO_FIRST = "bracketing_zero_first"
    }
}

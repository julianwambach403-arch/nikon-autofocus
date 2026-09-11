package de.nikonautofocus.app

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.nikonautofocus.app.ui.MainScreen
import de.nikonautofocus.app.ui.SettingsSheet
import de.nikonautofocus.app.ui.theme.NikonAutoFocusTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // A tethered focus watchdog is useless if the screen sleeps mid session.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            NikonAutoFocusTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val settings by viewModel.settings.collectAsStateWithLifecycle()
                val preview by viewModel.preview.collectAsStateWithLifecycle()

                var settingsOpen by remember { mutableStateOf(false) }
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

                MainScreen(
                    state = state,
                    settings = settings,
                    preview = preview,
                    onConnect = viewModel::connect,
                    onDisconnect = viewModel::disconnect,
                    onToggleMonitoring = viewModel::toggleMonitoring,
                    onFocusNow = viewModel::focusNow,
                    onCapturePhoto = viewModel::capturePhoto,
                    onToggleRecording = viewModel::toggleRecording,
                    onSetAppControl = viewModel::setAppControlsCamera,
                    onSetAfAreaMode = viewModel::setAfAreaMode,
                    onSetAfServoMode = viewModel::setAfServoMode,
                    onTapFocusPoint = viewModel::setAfPoint,
                    onSetFieldEnabled = viewModel::setManualFieldEnabled,
                    onSetFieldSize = viewModel::setManualFieldSize,
                    onSetCameraProperty = viewModel::setCameraProperty,
                    onRefreshDevices = viewModel::refreshDeviceState,
                    onDismissMessages = viewModel::dismissMessages,
                    onOpenSettings = { settingsOpen = true }
                )

                if (settingsOpen) {
                    SettingsSheet(
                        settings = settings,
                        sheetState = sheetState,
                        onDismiss = { settingsOpen = false },
                        onChange = { transform -> viewModel.updateSettings(transform) },
                        onReset = viewModel::resetSettings
                    )
                }
            }
        }

        handleUsbIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshDeviceState()
    }

    /**
     * The manifest declares a USB_DEVICE_ATTACHED filter, so plugging the camera in brings
     * the app to the front. Android grants access to the device that triggered the filter,
     * which means the connect flow usually does not need a separate permission dialog.
     */
    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            viewModel.refreshDeviceState()
        }
    }
}

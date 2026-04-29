package de.aarondietz.beetmeister.ui

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.aarondietz.beetmeister.beet.BeetConnectionPhase
import de.aarondietz.beetmeister.beet.BeetRepository
import de.aarondietz.beetmeister.ui.calibration.CalibrationScreen
import de.aarondietz.beetmeister.ui.composable.Header
import de.aarondietz.beetmeister.ui.connection.ConnectionGate
import de.aarondietz.beetmeister.ui.events.EventDetailScreen
import de.aarondietz.beetmeister.ui.events.EventsScreen
import de.aarondietz.beetmeister.ui.overview.OverviewScreen
import de.aarondietz.beetmeister.ui.pairdetail.PairDetailScreen
import de.aarondietz.beetmeister.ui.settings.SettingsScreen
import kotlinx.coroutines.delay

private const val UI_TAG = "BeetAppUi"
private const val CONNECTED_UI_STABILITY_MS = 900L

private enum class TopLevelScreen(val label: String, val icon: @Composable () -> Unit) {
    Overview("Overview", { Icon(Icons.Default.Grass, contentDescription = null) }),
    Calibration("Calibration", { Icon(Icons.Default.Tune, contentDescription = null) }),
    Events("Events", { Icon(Icons.Default.Event, contentDescription = null) }),
    Settings("Settings", { Icon(Icons.Default.Settings, contentDescription = null) }),
}

@Composable
internal fun BeetMeisterApp(viewModel: BeetAppViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var topLevelScreen by rememberSaveable { mutableStateOf(TopLevelScreen.Overview) }
    var selectedPair by rememberSaveable { mutableIntStateOf(0) }
    var pairDetailReturnScreen by rememberSaveable { mutableStateOf(TopLevelScreen.Overview) }
    var showEventTable by rememberSaveable { mutableStateOf(false) }
    var connectionGateVisible by rememberSaveable { mutableStateOf(true) }
    var permissionRequestAttempted by rememberSaveable { mutableStateOf(false) }

    val permissionsPermanentlyDenied =
        state.connection.phase == BeetConnectionPhase.PermissionsRequired &&
            permissionRequestAttempted &&
            activity != null &&
            BeetRepository.requiredPermissions().any { permission ->
                ContextCompat.checkSelfPermission(context, permission) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    !activity.shouldShowRequestPermissionRationale(permission)
            }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.refreshEnvironment()
        viewModel.startScan()
    }
    val activityLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.refreshEnvironment()
        viewModel.startScan()
    }

    LaunchedEffect(state.lastCommandMessage) {
        if (state.lastCommandMessage != null) {
            delay(4_000)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(state.connection.phase, state.selectedAddress) {
        Log.d(
            UI_TAG,
            "phase=${state.connection.phase} detail=${state.connection.detail} selected=${state.selectedAddress} gateVisibleBefore=$connectionGateVisible",
        )
        if (state.connection.phase == BeetConnectionPhase.Connected) {
            delay(CONNECTED_UI_STABILITY_MS)
            if (state.connection.phase == BeetConnectionPhase.Connected) {
                connectionGateVisible = false
                Log.d(UI_TAG, "Leaving connection gate after stable connected window")
            }
        } else {
            if (!connectionGateVisible) {
                Log.d(UI_TAG, "Showing connection gate because phase=${state.connection.phase}")
            }
            connectionGateVisible = true
        }
    }

    BackHandler(enabled = selectedPair != 0) {
        topLevelScreen = pairDetailReturnScreen
        selectedPair = 0
    }

    if (connectionGateVisible) {
        ConnectionGate(
            state = state,
            permissionsPermanentlyDenied = permissionsPermanentlyDenied,
            onRequestPermissions = {
                permissionRequestAttempted = true
                if (permissionsPermanentlyDenied) {
                    activityLauncher.launch(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                } else {
                    permissionLauncher.launch(BeetRepository.requiredPermissions())
                }
            },
            onRequestBluetooth = {
                activityLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            },
            onScan = viewModel::startScan,
            onConnect = viewModel::connect,
            modifier = modifier,
        )
        return
    }

    NavigationSuiteScaffold(
        modifier = modifier.fillMaxSize(),
        navigationSuiteItems = {
            TopLevelScreen.entries.forEach { destination ->
                item(
                    icon = destination.icon,
                    label = { Text(destination.label) },
                    selected = topLevelScreen == destination,
                    onClick = {
                        topLevelScreen = destination
                        selectedPair = 0
                        showEventTable = false
                    },
                )
            }
        },
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Header(state = state)
                state.lastCommandMessage?.let { message ->
                    AssistChip(
                        onClick = viewModel::clearMessage,
                        label = { Text(message) },
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }

                when {
                    selectedPair != 0 -> PairDetailScreen(
                        pairState = state.pairStates.first { it.pairIndex == selectedPair },
                        onBack = {
                            topLevelScreen = pairDetailReturnScreen
                            selectedPair = 0
                        },
                        onToggleEnabled = viewModel::togglePairEnabled,
                        onManualStart = viewModel::manualStart,
                        onManualStop = viewModel::manualStop,
                        onMoistureTestStart = viewModel::moistureTestStart,
                        onClearError = viewModel::clearPairError,
                        modifier = Modifier.fillMaxSize(),
                    )

                    showEventTable -> EventDetailScreen(
                        state = state,
                        onBack = { showEventTable = false },
                        onReload = { viewModel.loadRecentEvents() },
                        modifier = Modifier.fillMaxSize(),
                    )

                    topLevelScreen == TopLevelScreen.Overview -> OverviewScreen(
                        state = state,
                        onPairSelected = {
                            pairDetailReturnScreen = topLevelScreen
                            selectedPair = it
                        },
                        onClearError = viewModel::clearPairError,
                        onToggleEnabled = viewModel::togglePairEnabled,
                        modifier = Modifier.fillMaxSize(),
                    )

                    topLevelScreen == TopLevelScreen.Calibration -> CalibrationScreen(
                        state = state,
                        onRefresh = viewModel::refreshCalibrations,
                        onSave = viewModel::saveCalibration,
                        modifier = Modifier.fillMaxSize(),
                    )

                    topLevelScreen == TopLevelScreen.Events -> EventsScreen(
                        state = state,
                        onLoadDetails = {
                            viewModel.loadRecentEvents()
                            showEventTable = true
                        },
                        onRefresh = viewModel::refreshHistorySummary,
                        modifier = Modifier.fillMaxSize(),
                    )

                    else -> SettingsScreen(
                        state = state,
                        onDisconnect = viewModel::disconnect,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

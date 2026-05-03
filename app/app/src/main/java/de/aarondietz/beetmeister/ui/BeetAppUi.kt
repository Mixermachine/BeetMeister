package de.aarondietz.beetmeister.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AssistChip
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.aarondietz.beetmeister.data.repository.BeetRepository
import de.aarondietz.beetmeister.model.connection.BeetConnectionPhase
import de.aarondietz.beetmeister.model.repository.BeetRepositoryState
import de.aarondietz.beetmeister.ui.core.app.AppMainContentRouter
import de.aarondietz.beetmeister.ui.core.app.TopLevelScreen
import de.aarondietz.beetmeister.ui.core.app.findActivity
import de.aarondietz.beetmeister.ui.core.component.Header
import de.aarondietz.beetmeister.ui.feature.connection.ConnectionGate
import kotlinx.coroutines.delay

private const val UI_TAG = "BeetAppUi"
private const val CONNECTED_UI_STABILITY_MS = 900L

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

    val permissionController = rememberPermissionController(
        state = state,
        activity = activity,
        contextPackageName = context.packageName,
        hasPermission = { permission ->
            ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        },
        shouldShowRequestPermissionRationale = { permission ->
            activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        },
        permissionRequestAttempted = permissionRequestAttempted,
        onPermissionRequestAttempted = { permissionRequestAttempted = true },
        refreshEnvironment = viewModel::refreshEnvironment,
        startScan = viewModel::startScan,
    )

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
            permissionsPermanentlyDenied = permissionController.permissionsPermanentlyDenied,
            onRequestPermissions = permissionController.requestPermissions,
            onRequestBluetooth = permissionController.requestBluetoothEnable,
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
                AppMainContentRouter(
                    state = state,
                    topLevelScreen = topLevelScreen,
                    selectedPair = selectedPair,
                    showEventTable = showEventTable,
                    onSelectedPairChange = { pairIndex ->
                        pairDetailReturnScreen = topLevelScreen
                        selectedPair = pairIndex
                    },
                    onPairDetailBack = {
                        topLevelScreen = pairDetailReturnScreen
                        selectedPair = 0
                    },
                    onShowEventTableChange = { showEventTable = it },
                    onToggleEnabled = viewModel::togglePairEnabled,
                    onManualStart = viewModel::manualStart,
                    onManualStop = viewModel::manualStop,
                    onMoistureTestStart = viewModel::moistureTestStart,
                    onClearError = viewModel::clearPairError,
                    onLoadRecentEvents = viewModel::loadRecentEvents,
                    onRefreshHistorySummary = viewModel::refreshHistorySummary,
                    onRefreshCalibrations = viewModel::refreshCalibrations,
                    onSaveCalibration = viewModel::saveCalibration,
                    onDisconnect = viewModel::disconnect,
                )
            }
        }
    }
}

@Composable
private fun rememberPermissionController(
    state: BeetRepositoryState,
    activity: android.app.Activity?,
    contextPackageName: String,
    hasPermission: (String) -> Boolean,
    shouldShowRequestPermissionRationale: (String) -> Boolean,
    permissionRequestAttempted: Boolean,
    onPermissionRequestAttempted: () -> Unit,
    refreshEnvironment: () -> Unit,
    startScan: () -> Unit,
): PermissionController {
    val permissionsPermanentlyDenied =
        state.connection.phase == BeetConnectionPhase.PermissionsRequired &&
            permissionRequestAttempted &&
            activity != null &&
            BeetRepository.requiredPermissions().any { permission ->
                !hasPermission(permission) && !shouldShowRequestPermissionRationale(permission)
            }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        refreshEnvironment()
        startScan()
    }
    val activityLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        refreshEnvironment()
        startScan()
    }

    return remember(
        permissionsPermanentlyDenied,
        permissionLauncher,
        activityLauncher,
        contextPackageName,
        onPermissionRequestAttempted,
    ) {
        PermissionController(
            permissionsPermanentlyDenied = permissionsPermanentlyDenied,
            requestPermissions = {
                onPermissionRequestAttempted()
                if (permissionsPermanentlyDenied) {
                    activityLauncher.launch(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", contextPackageName, null),
                        ),
                    )
                } else {
                    permissionLauncher.launch(BeetRepository.requiredPermissions())
                }
            },
            requestBluetoothEnable = {
                activityLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            },
        )
    }
}

private data class PermissionController(
    val permissionsPermanentlyDenied: Boolean,
    val requestPermissions: () -> Unit,
    val requestBluetoothEnable: () -> Unit,
)

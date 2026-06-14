package de.aarondietz.beetmeister.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import de.aarondietz.beetmeister.data.repository.BeetRepository
import de.aarondietz.beetmeister.model.connection.BeetConnectionPhase
import de.aarondietz.beetmeister.model.controller.BeetValveConfig
import de.aarondietz.beetmeister.model.repository.BeetRepositoryState
import de.aarondietz.beetmeister.ui.core.app.AppMainContentRouter
import de.aarondietz.beetmeister.ui.core.app.TopLevelScreen
import de.aarondietz.beetmeister.ui.core.app.findActivity
import de.aarondietz.beetmeister.ui.core.component.Header
import de.aarondietz.beetmeister.ui.core.component.UnsavedChangesDialog
import de.aarondietz.beetmeister.ui.feature.calibration.CalibrationSaveDraft
import de.aarondietz.beetmeister.ui.feature.connection.ConnectionGate
import de.aarondietz.beetmeister.ui.feature.settings.SettingsSaveDraft
import kotlinx.coroutines.delay

private const val UI_TAG = "BeetAppUi"
private const val CONNECTED_UI_STABILITY_MS = 900L

private enum class UnsavedDialogSource {
    ValveCalibration,
    Settings,
    PairCalibration,
}

private enum class PendingActionKind {
    None,
    TopLevel,
    OpenValveCalibration,
    CloseValveCalibration,
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
    var showValveCalibration by rememberSaveable { mutableStateOf(false) }
    var showValveCalibrationUnsavedDialog by rememberSaveable { mutableStateOf(false) }
    var unsavedDialogSource by rememberSaveable { mutableStateOf<UnsavedDialogSource?>(null) }
    var pendingActionKind by rememberSaveable { mutableStateOf(PendingActionKind.None) }
    var pendingTopLevelScreen by rememberSaveable { mutableStateOf<TopLevelScreen?>(null) }
    var connectionGateVisible by rememberSaveable { mutableStateOf(true) }
    var permissionRequestAttempted by rememberSaveable { mutableStateOf(false) }
    var valveCalibrationHasUnsavedChanges by remember { mutableStateOf(false) }
    var valveCalibrationSavableConfig by remember { mutableStateOf<BeetValveConfig?>(null) }
    var settingsHasUnsavedChanges by remember { mutableStateOf(false) }
    var settingsSavableDraft by remember { mutableStateOf<SettingsSaveDraft?>(null) }
    var pairCalibrationHasUnsavedChanges by remember { mutableStateOf(false) }
    var pairCalibrationSavableDrafts by remember { mutableStateOf<List<CalibrationSaveDraft>?>(null) }

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

    BackHandler(enabled = showEventTable) {
        showEventTable = false
    }

    fun clearPendingLeaveState() {
        showValveCalibrationUnsavedDialog = false
        unsavedDialogSource = null
        pendingActionKind = PendingActionKind.None
        pendingTopLevelScreen = null
    }

    fun applyPendingAction() {
        val action = pendingActionKind
        val destination = pendingTopLevelScreen
        clearPendingLeaveState()
        when (action) {
            PendingActionKind.TopLevel -> {
                if (destination != null) {
                    topLevelScreen = destination
                    selectedPair = 0
                    showEventTable = false
                    showValveCalibration = false
                }
            }
            PendingActionKind.OpenValveCalibration -> {
                showValveCalibration = true
            }
            PendingActionKind.CloseValveCalibration -> {
                showValveCalibration = false
            }
            PendingActionKind.None -> {}
        }
    }

    fun finishLeavingValveCalibration() {
        showValveCalibration = false
        applyPendingAction()
    }

    fun requestLeaveValveCalibration(destination: TopLevelScreen? = null) {
        if (!showValveCalibration) {
            if (destination != null) {
                topLevelScreen = destination
                selectedPair = 0
                showEventTable = false
            }
            return
        }
        if (!valveCalibrationHasUnsavedChanges) {
            pendingActionKind = if (destination != null) PendingActionKind.TopLevel else PendingActionKind.CloseValveCalibration
            pendingTopLevelScreen = destination
            finishLeavingValveCalibration()
            return
        }
        unsavedDialogSource = UnsavedDialogSource.ValveCalibration
        pendingActionKind = if (destination != null) PendingActionKind.TopLevel else PendingActionKind.CloseValveCalibration
        pendingTopLevelScreen = destination
        showValveCalibrationUnsavedDialog = true
    }

    fun requestLeaveSettings(destination: TopLevelScreen? = null, openValveCalibration: Boolean = false) {
        if (topLevelScreen != TopLevelScreen.Settings || showValveCalibration) {
            if (openValveCalibration) {
                showValveCalibration = true
            } else if (destination != null) {
                topLevelScreen = destination
                selectedPair = 0
                showEventTable = false
            }
            return
        }
        if (!settingsHasUnsavedChanges) {
            pendingActionKind = when {
                openValveCalibration -> PendingActionKind.OpenValveCalibration
                destination != null -> PendingActionKind.TopLevel
                else -> PendingActionKind.None
            }
            pendingTopLevelScreen = destination
            applyPendingAction()
            return
        }
        unsavedDialogSource = UnsavedDialogSource.Settings
        pendingActionKind = when {
            openValveCalibration -> PendingActionKind.OpenValveCalibration
            destination != null -> PendingActionKind.TopLevel
            else -> PendingActionKind.None
        }
        pendingTopLevelScreen = destination
        showValveCalibrationUnsavedDialog = true
    }

    fun requestLeavePairCalibration(destination: TopLevelScreen) {
        if (topLevelScreen != TopLevelScreen.Calibration || showValveCalibration) {
            topLevelScreen = destination
            selectedPair = 0
            showEventTable = false
            return
        }
        if (!pairCalibrationHasUnsavedChanges) {
            pendingActionKind = PendingActionKind.TopLevel
            pendingTopLevelScreen = destination
            applyPendingAction()
            return
        }
        unsavedDialogSource = UnsavedDialogSource.PairCalibration
        pendingActionKind = PendingActionKind.TopLevel
        pendingTopLevelScreen = destination
        showValveCalibrationUnsavedDialog = true
    }

    BackHandler(enabled = showValveCalibration) {
        requestLeaveValveCalibration()
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

    if (showValveCalibrationUnsavedDialog) {
        val dialogTitle = when (unsavedDialogSource) {
            UnsavedDialogSource.Settings -> stringResource(de.aarondietz.beetmeister.R.string.settings_unsaved_title)
            UnsavedDialogSource.PairCalibration -> stringResource(de.aarondietz.beetmeister.R.string.calibration_unsaved_title)
            UnsavedDialogSource.ValveCalibration, null ->
                stringResource(de.aarondietz.beetmeister.R.string.valve_calibration_unsaved_title)
        }
        val dialogBody = when (unsavedDialogSource) {
            UnsavedDialogSource.Settings -> stringResource(de.aarondietz.beetmeister.R.string.settings_unsaved_body)
            UnsavedDialogSource.PairCalibration -> stringResource(de.aarondietz.beetmeister.R.string.calibration_unsaved_body)
            UnsavedDialogSource.ValveCalibration, null ->
                stringResource(de.aarondietz.beetmeister.R.string.valve_calibration_unsaved_body)
        }
        val saveEnabled = when (unsavedDialogSource) {
            UnsavedDialogSource.Settings -> settingsSavableDraft != null
            UnsavedDialogSource.PairCalibration -> pairCalibrationSavableDrafts != null
            UnsavedDialogSource.ValveCalibration, null -> valveCalibrationSavableConfig != null
        }
        UnsavedChangesDialog(
            title = dialogTitle,
            body = dialogBody,
            continueEditingLabel = stringResource(de.aarondietz.beetmeister.R.string.common_continue_editing),
            discardLabel = stringResource(de.aarondietz.beetmeister.R.string.common_discard),
            saveAndLeaveLabel = stringResource(de.aarondietz.beetmeister.R.string.common_save_and_leave),
            saveEnabled = saveEnabled,
            onContinueEditing = {
                clearPendingLeaveState()
            },
            onDiscard = {
                if (unsavedDialogSource == UnsavedDialogSource.ValveCalibration) {
                    finishLeavingValveCalibration()
                } else {
                    applyPendingAction()
                }
            },
            onSaveAndLeave = {
                when (unsavedDialogSource) {
                    UnsavedDialogSource.Settings -> {
                        settingsSavableDraft?.valveConfig?.let(viewModel::saveValveConfig)
                        settingsSavableDraft?.wateringIntervalSeconds?.let(viewModel::saveWateringInterval)
                        applyPendingAction()
                    }
                    UnsavedDialogSource.PairCalibration -> {
                        pairCalibrationSavableDrafts?.forEach { draft ->
                            viewModel.saveCalibration(draft.pairIndex, draft.dryMillivolts, draft.wetMillivolts)
                        }
                        applyPendingAction()
                    }
                    UnsavedDialogSource.ValveCalibration, null -> {
                        valveCalibrationSavableConfig?.let(viewModel::saveValveConfig)
                        finishLeavingValveCalibration()
                    }
                }
            },
        )
    }

    NavigationSuiteScaffold(
        modifier = modifier.fillMaxSize(),
        navigationSuiteItems = {
            TopLevelScreen.entries.forEach { destination ->
                item(
                    icon = destination.icon,
                    label = { Text(stringResource(destination.labelRes)) },
                    selected = topLevelScreen == destination,
                    onClick = {
                        if (showValveCalibration) {
                            requestLeaveValveCalibration(destination)
                        } else if (topLevelScreen == TopLevelScreen.Settings && destination != TopLevelScreen.Settings) {
                            requestLeaveSettings(destination = destination)
                        } else if (topLevelScreen == TopLevelScreen.Calibration && destination != TopLevelScreen.Calibration) {
                            requestLeavePairCalibration(destination)
                        } else {
                            topLevelScreen = destination
                            selectedPair = 0
                            showEventTable = false
                            showValveCalibration = false
                        }
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
                    showValveCalibration = showValveCalibration,
                    onSelectedPairChange = { pairIndex ->
                        pairDetailReturnScreen = topLevelScreen
                        selectedPair = pairIndex
                    },
                    onPairDetailBack = {
                        topLevelScreen = pairDetailReturnScreen
                        selectedPair = 0
                    },
                    onShowEventTableChange = { showEventTable = it },
                    onShowValveCalibrationChange = {
                        if (!showValveCalibration && it && topLevelScreen == TopLevelScreen.Settings) {
                            requestLeaveSettings(openValveCalibration = true)
                        } else if (showValveCalibration && !it) {
                            requestLeaveValveCalibration()
                        } else {
                            showValveCalibration = it
                        }
                    },
                    onToggleEnabled = viewModel::togglePairEnabled,
                    onManualStart = viewModel::manualStart,
                    onManualStop = viewModel::manualStop,
                    onMoistureTestStart = viewModel::moistureTestStart,
                    onClearError = viewModel::clearPairError,
                    onLoadRecentEvents = viewModel::loadRecentEvents,
                    onRefreshHistorySummary = viewModel::refreshHistorySummary,
                    onRefreshCalibrations = viewModel::refreshCalibrations,
                    onSaveCalibration = viewModel::saveCalibration,
                    onCalibrationUnsavedStateChange = { hasUnsaved, drafts ->
                        pairCalibrationHasUnsavedChanges = hasUnsaved
                        pairCalibrationSavableDrafts = drafts
                    },
                    onRefreshValveConfig = viewModel::refreshValveConfig,
                    onRefreshWateringInterval = viewModel::refreshWateringInterval,
                    onSaveValveConfig = viewModel::saveValveConfig,
                    onSaveWateringInterval = viewModel::saveWateringInterval,
                    onPreviewValvePosition = viewModel::previewValvePosition,
                    onValveCalibrationUnsavedStateChange = { hasUnsaved, savableConfig ->
                        valveCalibrationHasUnsavedChanges = hasUnsaved
                        valveCalibrationSavableConfig = savableConfig
                    },
                    onSettingsUnsavedStateChange = { hasUnsaved, draft ->
                        settingsHasUnsavedChanges = hasUnsaved
                        settingsSavableDraft = draft
                    },
                    onOpenValve = viewModel::openValve,
                    onCloseValve = viewModel::closeValve,
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

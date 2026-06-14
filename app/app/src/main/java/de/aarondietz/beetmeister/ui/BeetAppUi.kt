package de.aarondietz.beetmeister.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.aarondietz.beetmeister.data.repository.BeetRepository
import de.aarondietz.beetmeister.model.connection.BeetConnectionPhase
import de.aarondietz.beetmeister.model.controller.BeetValveConfig
import de.aarondietz.beetmeister.model.repository.BeetRepositoryState
import de.aarondietz.beetmeister.ui.core.app.AppMainContentRouter
import de.aarondietz.beetmeister.ui.core.app.AppRoute
import de.aarondietz.beetmeister.ui.core.app.AppRouteStackSaver
import de.aarondietz.beetmeister.ui.core.app.TopLevelScreen
import de.aarondietz.beetmeister.ui.core.app.findActivity
import de.aarondietz.beetmeister.ui.core.component.BeetPageScaffold
import de.aarondietz.beetmeister.ui.core.component.Header
import de.aarondietz.beetmeister.ui.core.component.UnsavedChangesDialog
import de.aarondietz.beetmeister.ui.feature.calibration.CalibrationSaveDraft
import de.aarondietz.beetmeister.ui.feature.connection.ConnectionGate
import de.aarondietz.beetmeister.ui.feature.settings.SettingsSaveDraft
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val UI_TAG = "BeetAppUi"
private const val CONNECTED_UI_STABILITY_MS = 900L

private enum class UnsavedDialogSource {
    ValveCalibration,
    Settings,
    PairCalibration,
}

@Composable
internal fun BeetMeisterApp(viewModel: BeetAppViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var routeStack by rememberSaveable(stateSaver = AppRouteStackSaver) {
        mutableStateOf(listOf<AppRoute>(AppRoute.TopLevel(TopLevelScreen.Overview)))
    }
    var pendingRouteStack by rememberSaveable {
        mutableStateOf<List<AppRoute>?>(null)
    }
    var showUnsavedDialog by rememberSaveable { mutableStateOf(false) }
    var unsavedDialogSource by rememberSaveable { mutableStateOf<UnsavedDialogSource?>(null) }
    var connectionGateVisible by rememberSaveable { mutableStateOf(true) }
    var permissionRequestAttempted by rememberSaveable { mutableStateOf(false) }
    var valveCalibrationHasUnsavedChanges by remember { mutableStateOf(false) }
    var valveCalibrationSavableConfig by remember { mutableStateOf<BeetValveConfig?>(null) }
    var settingsHasUnsavedChanges by remember { mutableStateOf(false) }
    var settingsSavableDraft by remember { mutableStateOf<SettingsSaveDraft?>(null) }
    var pairCalibrationHasUnsavedChanges by remember { mutableStateOf(false) }
    var pairCalibrationSavableDrafts by remember { mutableStateOf<List<CalibrationSaveDraft>?>(null) }

    val currentRoute = routeStack.last()

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

    fun clearPendingNavigation() {
        pendingRouteStack = null
        showUnsavedDialog = false
        unsavedDialogSource = null
    }

    fun applyPendingNavigation() {
        val target = pendingRouteStack
        clearPendingNavigation()
        if (target != null) {
            routeStack = target
        }
    }

    fun requestNavigation(targetStack: List<AppRoute>) {
        if (targetStack == routeStack) {
            return
        }
        when {
            currentRoute == AppRoute.ValveCalibration && valveCalibrationHasUnsavedChanges -> {
                pendingRouteStack = targetStack
                unsavedDialogSource = UnsavedDialogSource.ValveCalibration
                showUnsavedDialog = true
            }

            currentRoute is AppRoute.TopLevel &&
                currentRoute.screen == TopLevelScreen.Settings &&
                settingsHasUnsavedChanges -> {
                pendingRouteStack = targetStack
                unsavedDialogSource = UnsavedDialogSource.Settings
                showUnsavedDialog = true
            }

            currentRoute is AppRoute.TopLevel &&
                currentRoute.screen == TopLevelScreen.Calibration &&
                pairCalibrationHasUnsavedChanges -> {
                pendingRouteStack = targetStack
                unsavedDialogSource = UnsavedDialogSource.PairCalibration
                showUnsavedDialog = true
            }

            else -> routeStack = targetStack
        }
    }

    fun navigateTopLevel(screen: TopLevelScreen) {
        requestNavigation(listOf(AppRoute.TopLevel(screen)))
    }

    fun navigateBack() {
        if (routeStack.size > 1) {
            requestNavigation(routeStack.dropLast(1))
        }
    }

    fun navigateToPairDetail(pairIndex: Int) {
        requestNavigation(
            listOf(
                AppRoute.TopLevel(currentRoute.parentTopLevelScreen),
                AppRoute.PairDetail(
                    pairIndex = pairIndex,
                    parentTopLevelScreen = currentRoute.parentTopLevelScreen,
                ),
            ),
        )
    }

    fun navigateToWateringHistory() {
        requestNavigation(
            listOf(
                AppRoute.TopLevel(TopLevelScreen.Events),
                AppRoute.WateringHistory,
            ),
        )
    }

    fun navigateToValveCalibration() {
        requestNavigation(
            listOf(
                AppRoute.TopLevel(TopLevelScreen.Settings),
                AppRoute.ValveCalibration,
            ),
        )
    }

    BackHandler(enabled = routeStack.size > 1) {
        navigateBack()
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

    if (showUnsavedDialog) {
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
            onContinueEditing = ::clearPendingNavigation,
            onDiscard = ::applyPendingNavigation,
            onSaveAndLeave = {
                when (unsavedDialogSource) {
                    UnsavedDialogSource.Settings -> {
                        settingsSavableDraft?.valveConfig?.let(viewModel::saveValveConfig)
                        settingsSavableDraft?.wateringIntervalSeconds?.let(viewModel::saveWateringInterval)
                    }
                    UnsavedDialogSource.PairCalibration -> {
                        pairCalibrationSavableDrafts?.forEach { draft ->
                            viewModel.saveCalibration(draft.pairIndex, draft.dryMillivolts, draft.wetMillivolts)
                        }
                    }
                    UnsavedDialogSource.ValveCalibration, null -> {
                        valveCalibrationSavableConfig?.let(viewModel::saveValveConfig)
                    }
                }
                applyPendingNavigation()
            },
        )
    }

    val routeTitle = when (currentRoute) {
        is AppRoute.TopLevel -> if (currentRoute.screen == TopLevelScreen.Settings) {
            stringResource(currentRoute.screen.labelRes)
        } else {
            null
        }
        is AppRoute.PairDetail -> stringResource(de.aarondietz.beetmeister.R.string.common_pair_number, currentRoute.pairIndex)
        AppRoute.WateringHistory -> stringResource(de.aarondietz.beetmeister.R.string.events_watering_history_title)
        AppRoute.ValveCalibration -> stringResource(de.aarondietz.beetmeister.R.string.valve_calibration_title)
    }

    val headerContent: (@Composable () -> Unit)? = if (currentRoute == AppRoute.TopLevel(TopLevelScreen.Overview)) {
        { Header(state = state) }
    } else {
        null
    }

    val screenContent: @Composable (Modifier) -> Unit = { contentModifier ->
        Column(modifier = contentModifier.fillMaxSize()) {
            state.lastCommandMessage?.let { message ->
                AssistChip(
                    onClick = viewModel::clearMessage,
                    label = { Text(message) },
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            AppMainContentRouter(
                state = state,
                currentRoute = currentRoute,
                headerContent = headerContent,
                onOpenPairDetail = ::navigateToPairDetail,
                onOpenWateringHistory = ::navigateToWateringHistory,
                onOpenValveCalibration = ::navigateToValveCalibration,
                onNavigateBack = ::navigateBack,
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
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    if (currentRoute is AppRoute.TopLevel) {
        NavigationSuiteScaffold(
            modifier = modifier.fillMaxSize(),
            navigationSuiteItems = {
                TopLevelScreen.entries.forEach { destination ->
                    item(
                        icon = destination.icon,
                        label = { Text(stringResource(destination.labelRes)) },
                        selected = currentRoute.screen == destination,
                        onClick = { navigateTopLevel(destination) },
                    )
                }
            },
        ) {
            BeetPageScaffold(
                title = routeTitle,
                onNavigateBack = null,
                modifier = Modifier.fillMaxSize(),
            ) { contentModifier, _ ->
                screenContent(contentModifier)
            }
        }
    } else {
        BeetPageScaffold(
            title = routeTitle,
            onNavigateBack = ::navigateBack,
            modifier = modifier.fillMaxSize(),
            applyBottomSafeDrawing = true,
        ) { contentModifier, _ ->
            screenContent(contentModifier)
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

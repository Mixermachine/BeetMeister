package de.aarondietz.beetmeister.ui.core.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import de.aarondietz.beetmeister.ui.feature.calibration.CalibrationSaveDraft
import de.aarondietz.beetmeister.model.repository.BeetRepositoryState
import de.aarondietz.beetmeister.model.controller.BeetValveConfig
import de.aarondietz.beetmeister.ui.feature.calibration.CalibrationScreen
import de.aarondietz.beetmeister.ui.feature.events.EventDetailScreen
import de.aarondietz.beetmeister.ui.feature.events.EventsScreen
import de.aarondietz.beetmeister.ui.feature.overview.OverviewScreen
import de.aarondietz.beetmeister.ui.feature.pairdetail.PairDetailScreen
import de.aarondietz.beetmeister.ui.feature.settings.SettingsSaveDraft
import de.aarondietz.beetmeister.ui.feature.settings.SettingsScreen
import de.aarondietz.beetmeister.ui.feature.settings.ValveCalibrationScreen

@Composable
internal fun AppMainContentRouter(
    state: BeetRepositoryState,
    topLevelScreen: TopLevelScreen,
    selectedPair: Int,
    showEventTable: Boolean,
    showValveCalibration: Boolean,
    onSelectedPairChange: (Int) -> Unit,
    onPairDetailBack: () -> Unit,
    onShowEventTableChange: (Boolean) -> Unit,
    onShowValveCalibrationChange: (Boolean) -> Unit,
    onToggleEnabled: (Int) -> Unit,
    onManualStart: (Int, Int?) -> Unit,
    onManualStop: (Int) -> Unit,
    onMoistureTestStart: (Int) -> Unit,
    onClearError: (Int) -> Unit,
    onLoadRecentEvents: () -> Unit,
    onRefreshHistorySummary: () -> Unit,
    onRefreshCalibrations: () -> Unit,
    onSaveCalibration: (Int, Int, Int) -> Unit,
    onCalibrationUnsavedStateChange: (Boolean, List<CalibrationSaveDraft>?) -> Unit,
    onRefreshValveConfig: () -> Unit,
    onRefreshWateringInterval: () -> Unit,
    onSaveValveConfig: (BeetValveConfig) -> Unit,
    onSaveWateringInterval: (Int) -> Unit,
    onPreviewValvePosition: (Int) -> Unit,
    onValveCalibrationUnsavedStateChange: (Boolean, BeetValveConfig?) -> Unit,
    onSettingsUnsavedStateChange: (Boolean, SettingsSaveDraft?) -> Unit,
    onOpenValve: () -> Unit,
    onCloseValve: () -> Unit,
    onOpenFirmwareUpdate: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        selectedPair != 0 -> PairDetailScreen(
            pairState = state.pairStates.first { it.pairIndex == selectedPair },
            onBack = onPairDetailBack,
            onToggleEnabled = onToggleEnabled,
            onManualStart = onManualStart,
            onManualStop = onManualStop,
            onMoistureTestStart = onMoistureTestStart,
            onClearError = onClearError,
            modifier = modifier.fillMaxSize(),
        )

        showEventTable -> EventDetailScreen(
            state = state,
            onBack = { onShowEventTableChange(false) },
            onReload = onLoadRecentEvents,
            modifier = modifier.fillMaxSize(),
        )

        showValveCalibration -> ValveCalibrationScreen(
            state = state,
            onBack = { onShowValveCalibrationChange(false) },
            onRefreshValveConfig = onRefreshValveConfig,
            onPreviewValvePosition = onPreviewValvePosition,
            onSaveValveConfig = onSaveValveConfig,
            onUnsavedStateChange = onValveCalibrationUnsavedStateChange,
            modifier = modifier.fillMaxSize(),
        )

        topLevelScreen == TopLevelScreen.Overview -> OverviewScreen(
            state = state,
            onPairSelected = onSelectedPairChange,
            onClearError = onClearError,
            onToggleEnabled = onToggleEnabled,
            modifier = modifier.fillMaxSize(),
        )

        topLevelScreen == TopLevelScreen.Calibration -> CalibrationScreen(
            state = state,
            onRefresh = onRefreshCalibrations,
            onSave = onSaveCalibration,
            onUnsavedStateChange = onCalibrationUnsavedStateChange,
            modifier = modifier.fillMaxSize(),
        )

        topLevelScreen == TopLevelScreen.Events -> EventsScreen(
            state = state,
            onLoadDetails = {
                onLoadRecentEvents()
                onShowEventTableChange(true)
            },
            onRefresh = onRefreshHistorySummary,
            modifier = modifier.fillMaxSize(),
        )

        else -> SettingsScreen(
            state = state,
            onRefreshValveConfig = onRefreshValveConfig,
            onRefreshWateringInterval = onRefreshWateringInterval,
            onSaveValveConfig = onSaveValveConfig,
            onSaveWateringInterval = onSaveWateringInterval,
            onOpenValveCalibration = { onShowValveCalibrationChange(true) },
            onOpenValve = onOpenValve,
            onCloseValve = onCloseValve,
            onOpenFirmwareUpdate = onOpenFirmwareUpdate,
            onDisconnect = onDisconnect,
            onUnsavedStateChange = onSettingsUnsavedStateChange,
            modifier = modifier.fillMaxSize(),
        )
    }
}

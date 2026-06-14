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
    currentRoute: AppRoute,
    headerContent: (@Composable () -> Unit)?,
    onOpenPairDetail: (Int) -> Unit,
    onOpenWateringHistory: () -> Unit,
    onOpenValveCalibration: () -> Unit,
    onNavigateBack: () -> Unit,
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
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (currentRoute) {
        is AppRoute.PairDetail -> PairDetailScreen(
            pairState = state.pairStates.first { it.pairIndex == currentRoute.pairIndex },
            onBack = onNavigateBack,
            onToggleEnabled = onToggleEnabled,
            onManualStart = onManualStart,
            onManualStop = onManualStop,
            onMoistureTestStart = onMoistureTestStart,
            onClearError = onClearError,
            modifier = modifier.fillMaxSize(),
        )

        AppRoute.WateringHistory -> EventDetailScreen(
            state = state,
            onBack = onNavigateBack,
            onReload = onLoadRecentEvents,
            modifier = modifier.fillMaxSize(),
        )

        AppRoute.ValveCalibration -> ValveCalibrationScreen(
            state = state,
            onBack = onNavigateBack,
            onRefreshValveConfig = onRefreshValveConfig,
            onPreviewValvePosition = onPreviewValvePosition,
            onSaveValveConfig = onSaveValveConfig,
            onUnsavedStateChange = onValveCalibrationUnsavedStateChange,
            modifier = modifier.fillMaxSize(),
        )

        is AppRoute.TopLevel -> when (currentRoute.screen) {
            TopLevelScreen.Overview -> OverviewScreen(
                state = state,
                headerContent = headerContent,
                onPairSelected = onOpenPairDetail,
                onClearError = onClearError,
                onToggleEnabled = onToggleEnabled,
                modifier = modifier.fillMaxSize(),
            )

            TopLevelScreen.Calibration -> CalibrationScreen(
                state = state,
                onRefresh = onRefreshCalibrations,
                onSave = onSaveCalibration,
                onUnsavedStateChange = onCalibrationUnsavedStateChange,
                modifier = modifier.fillMaxSize(),
            )

            TopLevelScreen.Events -> EventsScreen(
                state = state,
                onLoadDetails = {
                    onLoadRecentEvents()
                    onOpenWateringHistory()
                },
                onRefresh = onRefreshHistorySummary,
                modifier = modifier.fillMaxSize(),
            )

            TopLevelScreen.Settings -> SettingsScreen(
                state = state,
                onRefreshValveConfig = onRefreshValveConfig,
                onRefreshWateringInterval = onRefreshWateringInterval,
                onSaveValveConfig = onSaveValveConfig,
                onSaveWateringInterval = onSaveWateringInterval,
                onOpenValveCalibration = onOpenValveCalibration,
                onOpenValve = onOpenValve,
                onCloseValve = onCloseValve,
                onDisconnect = onDisconnect,
                onUnsavedStateChange = onSettingsUnsavedStateChange,
                modifier = modifier.fillMaxSize(),
            )
        }
    }
}

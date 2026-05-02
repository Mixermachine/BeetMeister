package de.aarondietz.beetmeister.ui.composable

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import de.aarondietz.beetmeister.beet.BeetRepositoryState
import de.aarondietz.beetmeister.ui.calibration.CalibrationScreen
import de.aarondietz.beetmeister.ui.events.EventDetailScreen
import de.aarondietz.beetmeister.ui.events.EventsScreen
import de.aarondietz.beetmeister.ui.overview.OverviewScreen
import de.aarondietz.beetmeister.ui.pairdetail.PairDetailScreen
import de.aarondietz.beetmeister.ui.settings.SettingsScreen

@Composable
internal fun AppMainContentRouter(
    state: BeetRepositoryState,
    topLevelScreen: TopLevelScreen,
    selectedPair: Int,
    showEventTable: Boolean,
    onSelectedPairChange: (Int) -> Unit,
    onPairDetailBack: () -> Unit,
    onShowEventTableChange: (Boolean) -> Unit,
    onToggleEnabled: (Int) -> Unit,
    onManualStart: (Int, Int?) -> Unit,
    onManualStop: (Int) -> Unit,
    onMoistureTestStart: (Int) -> Unit,
    onClearError: (Int) -> Unit,
    onLoadRecentEvents: () -> Unit,
    onRefreshHistorySummary: () -> Unit,
    onRefreshCalibrations: () -> Unit,
    onSaveCalibration: (Int, Int, Int) -> Unit,
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
            onDisconnect = onDisconnect,
            modifier = modifier.fillMaxSize(),
        )
    }
}

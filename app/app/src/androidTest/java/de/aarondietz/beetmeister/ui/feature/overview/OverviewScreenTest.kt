package de.aarondietz.beetmeister.ui.feature.overview

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import de.aarondietz.beetmeister.model.controller.BeetControllerInfo
import de.aarondietz.beetmeister.model.controller.BeetPairState
import de.aarondietz.beetmeister.model.repository.BeetRepositoryState
import org.junit.Rule
import org.junit.Test

private val hasLoadingIndicator =
    SemanticsMatcher("has ProgressBarRangeInfo") { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null }

/**
 * Screen-level instrumented tests for [OverviewScreen].
 *
 * No ViewModel, repository, or BLE controller involved: the composable is
 * driven directly with a fake [BeetRepositoryState], and pair frames are
 * "pushed" by replacing the state, mirroring per-pair BLE notifications.
 *
 * Regression cover: pairs without a received frame must show a loading
 * indicator, never fabricated zero values, and a partially synced map must
 * never render one pair's data under another pair's label.
 */
class OverviewScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class Harness(val state: MutableState<BeetRepositoryState>)

    private fun setScreen(initial: BeetRepositoryState): Harness {
        val state = mutableStateOf(initial)
        composeRule.setContent {
            OverviewScreen(
                state = state.value,
                onPairSelected = {},
                onClearError = {},
                onToggleEnabled = {},
            )
        }
        composeRule.waitForIdle()
        return Harness(state)
    }

    private fun pairFrame(
        pairIndex: Int,
        moisturePercent: Int,
        sensorMillivolts: Int,
        sensorValid: Boolean = true,
    ) = BeetPairState(
        pairIndex = pairIndex,
        state = "IDLE",
        moisturePercent = moisturePercent,
        sensorMillivolts = sensorMillivolts,
        enabled = true,
        sensorValid = sensorValid,
        blocked = false,
        blockReason = "NONE",
        remainingSeconds = 0,
        source = "AUTOMATIC",
    )

    private fun loadingNode(pairIndex: Int) =
        composeRule.onAllNodesWithTag(OverviewTestTags.PairMoisture)[pairIndex - 1]

    private fun sensorNode(pairIndex: Int) =
        composeRule.onAllNodesWithTag(OverviewTestTags.PairSensor)[pairIndex - 1]

    /** Small pair count so all cards are composed inside the viewport (LazyColumn). */
    private fun stateWithPairCount(pairStates: Map<Int, BeetPairState>) = BeetRepositoryState(
        controllerInfo = BeetControllerInfo(
            deviceId = "test",
            protocolVersion = 1,
            firmwareVersion = "test",
            pairCount = 3,
        ),
        pairStates = pairStates,
    )

    @Test
    fun allPairsShowLoadingBeforeFirstFrame() {
        setScreen(stateWithPairCount(emptyMap()))

        // All three pair cards exist, none render fake "0%" or "0 mV" values.
        composeRule.onAllNodesWithTag(OverviewTestTags.PairCard).assertCountEquals(3)
        composeRule.onNodeWithText("0%").assertDoesNotExist()
        composeRule.onNodeWithText("0 mV").assertDoesNotExist()

        // Every moisture/sensor value slot shows the loading indicator.
        for (index in 1..3) {
            loadingNode(index).assert(hasLoadingIndicator)
            sensorNode(index).assert(hasLoadingIndicator)
        }
    }

    @Test
    fun firstFrameRendersValuesOthersStayLoading() {
        val harness = setScreen(BeetRepositoryState())

        harness.state.value = BeetRepositoryState(
            pairStates = mapOf(1 to pairFrame(1, moisturePercent = 58, sensorMillivolts = 1520)),
        )
        composeRule.waitForIdle()

        loadingNode(1).assert(hasText("58%"))
        sensorNode(1).assert(hasText("1520 mV"))
        loadingNode(2).assert(hasLoadingIndicator)
        sensorNode(2).assert(hasLoadingIndicator)
    }

    @Test
    fun partialSyncNeverMislabelsMissingPair() {
        // Frames for pairs 1 and 3 received; pair 2 missing. Each received
        // pair has a distinct moisture value equal to pairIndex * 10.
        val frames = listOf(1, 3).associateWith { index ->
            pairFrame(index, moisturePercent = index * 10, sensorMillivolts = index * 100)
        }
        setScreen(stateWithPairCount(frames))

        composeRule.onAllNodesWithTag(OverviewTestTags.PairCard).assertCountEquals(3)
        // Position can never lie: card 2 is a spinner, card 3 shows pair 3's data.
        loadingNode(2).assert(hasLoadingIndicator)
        loadingNode(3).assert(hasText("30%"))
        composeRule.onAllNodesWithTag(OverviewTestTags.PairName).assertCountEquals(3)
        composeRule.onAllNodesWithTag(OverviewTestTags.PairName)[1].assert(hasText("Pair 2"))
    }

    @Test
    fun invalidSensorFrameStillShowsValues() {
        setScreen(
            BeetRepositoryState(
                pairStates = mapOf(
                    1 to pairFrame(1, moisturePercent = 0, sensorMillivolts = 3900, sensorValid = false),
                ),
            ),
        )

        // sensor_valid=false frames must remain visible for diagnostics,
        // not be replaced by the loading indicator.
        sensorNode(1).assert(hasText("3900 mV"))
        loadingNode(1).assert(hasText("0%"))
    }

    @Test
    fun enableToggleDisabledUntilFrameArrives() {
        val harness = setScreen(BeetRepositoryState())

        composeRule.onAllNodesWithTag(OverviewTestTags.PairEnableToggle)[0].assertIsNotEnabled()

        harness.state.value = BeetRepositoryState(
            pairStates = mapOf(1 to pairFrame(1, moisturePercent = 40, sensorMillivolts = 1500)),
        )
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag(OverviewTestTags.PairEnableToggle)[0].assertIsEnabled()
    }
}

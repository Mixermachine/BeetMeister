package de.aarondietz.beetmeister.ui.feature.pairdetail

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import de.aarondietz.beetmeister.model.controller.BeetPairState
import org.junit.Rule
import org.junit.Test

/**
 * Screen-level instrumented tests for [PairDetailScreen] with no controller:
 * the composable is rendered directly with a nullable `pairState`, mirroring
 * the "pair detail opened before the first pair_state frame arrived" case
 * (formerly a hard `first { }` lookup crash in AppMainContentRouter).
 */
class PairDetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class Harness(val pairState: MutableState<BeetPairState?>)

    private fun pairFrame(
        pairIndex: Int,
        moisturePercent: Int = 58,
        sensorMillivolts: Int = 1520,
    ) = BeetPairState(
        pairIndex = pairIndex,
        state = "IDLE",
        moisturePercent = moisturePercent,
        sensorMillivolts = sensorMillivolts,
        enabled = true,
        sensorValid = true,
        blocked = false,
        blockReason = "NONE",
        remainingSeconds = 0,
        source = "AUTOMATIC",
    )

    private fun setScreen(initial: BeetPairState?): Harness {
        val pairState = mutableStateOf(initial)
        composeRule.setContent {
            PairDetailScreen(
                pairState = pairState.value,
                pairIndex = 1,
                pairWiring = null,
                pairWiringLoading = false,
                pairWiringError = null,
                pairName = null,
                onStorePairName = { _, _ -> },
                onBack = {},
                onLoadPairWiring = {},
                onToggleEnabled = {},
                onManualStart = { _, _ -> },
                onManualStop = {},
                onMoistureTestStart = {},
                onClearError = {},
            )
        }
        composeRule.waitForIdle()
        return Harness(pairState)
    }

    @Test
    fun loadingValuesAndDisabledActionsBeforeFirstFrame() {
        setScreen(null)

        composeRule.onNodeWithTag(PairDetailTestTags.MoistureValue).assert(hasLoadingIndicator)
        composeRule.onNodeWithTag(PairDetailTestTags.ManualStartButton).assertIsNotEnabled()
        composeRule.onNodeWithTag(PairDetailTestTags.EnabledToggle).assertIsNotEnabled()
        composeRule.onNodeWithTag(PairDetailTestTags.Name).assert(hasText("Pair 1"))
    }

    @Test
    fun valuesRenderedAfterFrameArrives() {
        val harness = setScreen(null)

        harness.pairState.value = pairFrame(1)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(PairDetailTestTags.MoistureValue).assert(hasText("58%"))
        composeRule.onNodeWithTag(PairDetailTestTags.ManualStartButton).assertIsDisplayed()
    }

    @Test
    fun invalidSensorFrameStillShowsValues() {
        setScreen(pairFrame(1, moisturePercent = 0, sensorMillivolts = 3900).copy(sensorValid = false))

        composeRule.onNodeWithTag(PairDetailTestTags.MoistureValue).assert(hasText("0%"))
        composeRule.onNodeWithTag(PairDetailTestTags.SensorValue).assert(hasText("3900 mV"))
    }
}

private val hasLoadingIndicator =
    SemanticsMatcher("has ProgressBarRangeInfo") { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null }

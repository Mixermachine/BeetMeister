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
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import de.aarondietz.beetmeister.model.controller.BeetPairCombined
import de.aarondietz.beetmeister.model.controller.BeetPairState
import org.junit.Assert.assertEquals
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

    private fun setScreen(
        initial: BeetPairState?,
        pairIndex: Int = 1,
        pairCombined: Map<Int, BeetPairCombined> = emptyMap(),
        isPairCombinedLoaded: Boolean = true,
        pairNames: Map<Int, String> = emptyMap(),
        onSetPairSensorSource: (Int, Int?) -> Unit = { _, _ -> },
    ): Harness {
        val pairState = mutableStateOf(initial)
        composeRule.setContent {
            PairDetailScreen(
                pairState = pairState.value,
                pairIndex = pairIndex,
                pairWiring = null,
                pairWiringLoading = false,
                pairWiringError = null,
                pairName = pairNames[pairIndex],
                pairCombined = pairCombined,
                isPairCombinedLoaded = isPairCombinedLoaded,
                pairNames = pairNames,
                onSetPairSensorSource = onSetPairSensorSource,
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
        composeRule.onNodeWithTag(PairDetailTestTags.Container)
            .performScrollToNode(hasTestTag(PairDetailTestTags.ManualStartButton))
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
        composeRule.onNodeWithTag(PairDetailTestTags.Container)
            .performScrollToNode(hasTestTag(PairDetailTestTags.ManualStartButton))
        composeRule.onNodeWithTag(PairDetailTestTags.ManualStartButton).assertIsDisplayed()
    }

    @Test
    fun invalidSensorFrameStillShowsValues() {
        setScreen(pairFrame(1, moisturePercent = 0, sensorMillivolts = 3900).copy(sensorValid = false))

        composeRule.onNodeWithTag(PairDetailTestTags.MoistureValue).assert(hasText("0%"))
        composeRule.onNodeWithTag(PairDetailTestTags.SensorValue).assert(hasText("3900 mV"))
    }

    @Test
    fun dedicatedSensorSelectedByDefault() {
        setScreen(pairFrame(1))

        composeRule.onNodeWithTag(PairDetailTestTags.Container)
            .performScrollToNode(hasTestTag(PairDetailTestTags.SensorSourceCard))
        composeRule.onNodeWithTag(PairDetailTestTags.SensorSourceCard).assertIsDisplayed()
        composeRule.onNodeWithTag(PairDetailTestTags.SensorSourceDedicatedRadio).assertIsSelected()
    }

    @Test
    fun followerDisplaysSharedSensorAndLeadInfo() {
        // Pair 2 follows Pair 1
        val combined = mapOf(1 to BeetPairCombined(pairIndex = 1, followersMask = (1 shl 1)))
        val names = mapOf(1 to "Tomato Bed")
        setScreen(pairFrame(2), pairIndex = 2, pairCombined = combined, pairNames = names)

        composeRule.onNodeWithTag(PairDetailTestTags.Container)
            .performScrollToNode(hasTestTag(PairDetailTestTags.SensorSourceCard))
        composeRule.onNodeWithTag(PairDetailTestTags.SensorSourceSharedRadio).assertIsSelected()
        composeRule.onNodeWithTag(PairDetailTestTags.SensorSourceDropdown).assertIsDisplayed()
        composeRule.onNodeWithTag(PairDetailTestTags.SensorSourceDropdown).assert(hasText("Tomato Bed (Pair 1)"))
    }

    @Test
    fun switchingSensorSourceDispatchesCallback() {
        var dispatchedPair: Int? = null
        var dispatchedLead: Int? = null

        setScreen(
            pairFrame(2),
            pairIndex = 2,
            onSetPairSensorSource = { p, l ->
                dispatchedPair = p
                dispatchedLead = l
            },
        )

        composeRule.onNodeWithTag(PairDetailTestTags.Container)
            .performScrollToNode(hasTestTag(PairDetailTestTags.SensorSourceSharedRadio))
        composeRule.onNodeWithTag(PairDetailTestTags.SensorSourceSharedRadio).performClick()
        composeRule.waitForIdle()

        assertEquals(2, dispatchedPair)
        assertEquals(1, dispatchedLead)
    }

    @Test
    fun sensorSourceLockedWhenCombinedNotLoaded() {
        setScreen(
            pairFrame(1),
            pairIndex = 1,
            isPairCombinedLoaded = false,
        )

        composeRule.onNodeWithTag(PairDetailTestTags.Container)
            .performScrollToNode(hasTestTag(PairDetailTestTags.SensorSourceCard))
        composeRule.onNodeWithTag(PairDetailTestTags.SensorSourceLoading).assertIsDisplayed()
        composeRule.onNodeWithTag(PairDetailTestTags.SensorSourceDedicatedRadio).assertIsNotEnabled()
        composeRule.onNodeWithTag(PairDetailTestTags.SensorSourceSharedRadio).assertIsNotEnabled()
    }
}

private val hasLoadingIndicator =
    SemanticsMatcher("has ProgressBarRangeInfo") { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null }

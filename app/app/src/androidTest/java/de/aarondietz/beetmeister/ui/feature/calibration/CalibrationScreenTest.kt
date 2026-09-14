package de.aarondietz.beetmeister.ui.feature.calibration

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import de.aarondietz.beetmeister.model.controller.BeetCalibration
import de.aarondietz.beetmeister.model.controller.BeetPairState
import de.aarondietz.beetmeister.model.repository.BeetRepositoryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Screen-level instrumented tests for [CalibrationScreen].
 *
 * These drive the composable directly with a fake [BeetRepositoryState];
 * no ViewModel, repository, or BLE controller is involved.
 */
class CalibrationScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class Harness(
        val state: MutableState<BeetRepositoryState>,
        val unsaved: MutableState<Pair<Boolean, List<CalibrationSaveDraft>?>?>,
        val saved: MutableList<Triple<Int, Int, Int>>,
    )

    private fun setScreen(initial: BeetRepositoryState): Harness {
        val state = mutableStateOf(initial)
        val unsaved = mutableStateOf<Pair<Boolean, List<CalibrationSaveDraft>?>?>(null)
        val saved = mutableListOf<Triple<Int, Int, Int>>()
        composeRule.setContent {
            CalibrationScreen(
                state = state.value,
                onRefresh = {},
                onSave = { pair, dry, wet -> saved += Triple(pair, dry, wet) },
                onUnsavedStateChange = { dirty, drafts -> unsaved.value = dirty to drafts },
            )
        }
        composeRule.waitForIdle()
        return Harness(state, unsaved, saved)
    }

    private fun baseState(
        calibrations: Map<Int, BeetCalibration> = emptyMap(),
        pairNames: Map<Int, String> = emptyMap(),
    ) = BeetRepositoryState(
        calibrations = calibrations,
        pairNames = pairNames,
    )

    private fun withPairSensorMillivolts(
        state: BeetRepositoryState,
        pairIndex: Int,
        millivolts: Int,
    ): BeetRepositoryState {
        val pairStates: List<BeetPairState> = state.pairStates.map { pair ->
            if (pair.pairIndex == pairIndex) pair.copy(sensorMillivolts = millivolts) else pair
        }
        return state.copy(pairStates = pairStates)
    }

    private fun calibration(pairIndex: Int, dry: Int, wet: Int) = BeetCalibration(
        pairIndex = pairIndex,
        dryMillivolts = dry,
        wetMillivolts = wet,
        source = "APP",
        calibratedAtUnixSeconds = 0L,
    )

    @Test
    fun typedValuesSurviveLiveSensorUpdate() {
        val initial = baseState(calibrations = mapOf(1 to calibration(1, dry = 2000, wet = 800)))
        val harness = setScreen(initial)

        val dry = composeRule.onNodeWithTag(CalibrationTestTags.dryInput(1))
        dry.performTextClearance()
        dry.performTextInput("3000")
        dry.assert(hasText("3000"))

        // Push a live moisture-voltage update for the same pair while the
        // calibration data stays unchanged. The entered value must survive.
        val next = withPairSensorMillivolts(initial, pairIndex = 1, millivolts = 1234)
        composeRule.runOnIdle { harness.state.value = next }

        dry.assert(hasText("3000"))
        composeRule.onNodeWithTag(CalibrationTestTags.wetInput(1)).assert(hasText("800"))
    }

    @Test
    fun loadedCalibrationPopulatesFields() {
        val harness = setScreen(baseState())

        val next = baseState(calibrations = mapOf(1 to calibration(1, dry = 2100, wet = 900)))
        composeRule.runOnIdle { harness.state.value = next }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CalibrationTestTags.dryInput(1)).assert(hasText("2100"))
        composeRule.onNodeWithTag(CalibrationTestTags.wetInput(1)).assert(hasText("900"))
    }

    @Test
    fun pairNameShownWhenStored() {
        setScreen(baseState(pairNames = mapOf(1 to "Front Garden")))

        composeRule.onNodeWithTag(CalibrationTestTags.title(1)).assert(hasText("Front Garden"))
    }

    @Test
    fun blankPairNameFallsBackToPairNumber() {
        setScreen(baseState(pairNames = mapOf(1 to "   ")))

        composeRule.onNodeWithTag(CalibrationTestTags.title(1)).assert(hasText("Pair 1"))
    }

    @Test
    fun missingPairNameFallsBackToPairNumber() {
        setScreen(baseState())

        composeRule.onNodeWithTag(CalibrationTestTags.title(1)).assert(hasText("Pair 1"))
    }

    @Test
    fun validEditReportsSavableDraft() {
        val harness = setScreen(baseState())

        val dry = composeRule.onNodeWithTag(CalibrationTestTags.dryInput(1))
        val wet = composeRule.onNodeWithTag(CalibrationTestTags.wetInput(1))
        dry.performTextInput("3000")
        wet.performTextInput("1000")

        composeRule.waitUntil(timeoutMillis = 5_000) {
            harness.unsaved.value == (true to listOf(CalibrationSaveDraft(1, 3000, 1000)))
        }
        assertEquals(true, harness.unsaved.value!!.first)
        assertEquals(listOf(CalibrationSaveDraft(1, 3000, 1000)), harness.unsaved.value!!.second)
    }

    @Test
    fun invalidEditReportsUnsavedWithoutSavableDrafts() {
        val harness = setScreen(baseState())

        val dry = composeRule.onNodeWithTag(CalibrationTestTags.dryInput(1))
        val wet = composeRule.onNodeWithTag(CalibrationTestTags.wetInput(1))
        dry.performTextInput("1000")
        wet.performTextInput("3000")

        composeRule.waitUntil(timeoutMillis = 5_000) {
            harness.unsaved.value == (true to null)
        }
        assertTrue(harness.unsaved.value!!.first)
        assertNull(harness.unsaved.value!!.second)
    }
}

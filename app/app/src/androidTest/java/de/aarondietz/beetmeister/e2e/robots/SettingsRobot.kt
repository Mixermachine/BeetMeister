package de.aarondietz.beetmeister.e2e.robots

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.platform.app.InstrumentationRegistry
import de.aarondietz.beetmeister.R
import de.aarondietz.beetmeister.ui.NavigationSuiteTestTags
import de.aarondietz.beetmeister.ui.feature.connection.MaintenanceUpdateTestTags
import de.aarondietz.beetmeister.ui.feature.settings.SettingsTestTags

/**
 * Robot for the Settings screen (the third top-level destination).
 *
 * The Settings screen hosts the E2E settings-update suite's full
 * surface: every writable setting is a card on this LazyColumn, and
 * every card has a stable test tag from [SettingsTestTags].
 *
 * Per the plan, the settings readback pattern is **UI readback only**:
 * the robot sets a deterministic B, taps save, and asserts the
 * taggable readback node. For settings with a read-only current-value
 * row ([SettingsTestTags.WateringIntervalCurrent] etc.) the readback
 * is the row's text. For the three valve-numeric fields (move / settle
 * / hold) which have no read-only row, the robot types B, saves, then
 * pull-to-refreshes the Settings screen (BeetPullToRefreshBox re-fetches
 * valveConfig, `LaunchedEffect(valveConfig)` repopulates the field text)
 * and asserts the field text == B.
 *
 * Settings robot also owns the firmware-update subflow
 * ([openFirmwareUpdate], [useBundled], [tapInstall]) because the
 * firmware update is reached via a card on this screen; the
 * [FirmwareUpdateRobot] wraps the post-open state-machine.
 */
internal class SettingsRobot(
    private val composeRule: ComposeTestRule,
) {
    /**
     * Localized "Yes" / "No" strings for the valve-enabled readback
     * and the read-only cells that show a localized affirmative.
     * Resolved from the app's resources via the instrumentation
     * target context so the robot respects the device locale
     * (German "Ja" / "Nein", etc.). Hardcoding English would break
     * non-English phones.
     */
    private val yesLabel: String by lazy {
        InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getString(R.string.common_yes)
    }
    private val noLabel: String by lazy {
        InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getString(R.string.common_no)
    }

    /**
     * The "—" placeholder the Settings screen shows for unloaded
     * cells. The non-empty assertions in this robot must reject
     * the placeholder, not just any non-empty string, because
     * Compose's [assertTextContains] is satisfied by *any* text
     * (including "—"), which makes a literal `assertTextContains("")`
     * a no-op (per P2 review finding SUB #2).
     */
    private val placeholderDash: String by lazy {
        InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getString(R.string.placeholder_dash)
    }

    /**
     * Taps the Settings nav-item tag, then waits for the Settings
     * container to render.
     *
     * P5 finding SUB #R7: the `Modifier.testTag(NavigationSuiteTestTags.tagFor(...))`
     * is applied to the `Text` composable inside the Material3
     * `NavigationBarItem`'s `label` slot. `NavigationBarItem` uses
     * `mergeDescendants = true`, which merges child semantics into
     * the item's parent node but does NOT carry the child test tag
     * forward. The `fetchOneOrThrow` call therefore fails with
     * "the unmerged tree contains '1' node that matches" unless
     * the finder explicitly opts out of the merge with
     * `useUnmergedNode = true`. This is the same root cause as
     * [ConnectionGateRobot.postConnectVisible] and the
     * smoke-gate's `MaintenanceUpdateLiveActivityInstrumentationTest`
     * has-text probe — the test rule's merged-tree finder cannot
     * see tags applied inside `mergeDescendants` containers.
     */
    fun openSettings() {
        composeRule
            .onNodeWithTag(NavigationSuiteTestTags.SettingsNavItem, useUnmergedTree = true)
            .performClick()
        composeRule
            .onNodeWithTag(SettingsTestTags.Container)
            .assertIsDisplayed()
    }

    /**
     * Scrolls the Settings LazyColumn until the node tagged [tag]
     * is composed. Returns the `SemanticsNodeInteraction` for
     * that tag.
     *
     * The Settings screen is a LazyColumn wrapped in a
     * `BeetPullToRefreshBox` (the `SettingsTestTags.Container`).
     * The `PullToRefreshBox` breaks the scrollable-ancestor chain
     * in the semantics tree, so neither `performScrollTo` on the
     * container nor `performScrollToIndex` on a child finds the
     * LazyColumn's scrollable state. The fix is to drive a
     * bottom-to-top `swipe` gesture on a child of the LazyColumn
     * (the always-composed [SettingsTestTags.ControllerInfoCard]).
     * The child's swipe event bubbles up through the
     * `nestedScroll` connection to the LazyColumn, scrolling it
     * while the `PullToRefreshBox` only intercepts downward
     * top-of-list swipes. 20 iterations covers the full Settings
     * list on the A53.
     */
    private fun scrollToTag(tag: String) {
        val anchor = composeRule.onAllNodesWithTag(SettingsTestTags.ControllerInfoCard).onFirst()
        val bounds = anchor.fetchSemanticsNode().boundsInRoot
        val centerX = (bounds.left + bounds.right) / 2f
        repeat(20) {
            val found = composeRule
                .onAllNodesWithTag(tag)
                .fetchSemanticsNodes()
                .isNotEmpty()
            if (found) return@repeat
            anchor.performTouchInput {
                swipe(
                    start = Offset(centerX, bounds.bottom - 50f),
                    end = Offset(centerX, bounds.top + 50f),
                )
            }
            composeRule.waitForIdle()
        }
    }

    /** Asserts the controller info card is rendered. */
    fun assertControllerInfoDisplayed() {
        composeRule
            .onNodeWithTag(SettingsTestTags.ControllerInfoCard)
            .assertIsDisplayed()
    }

    /**
     * Asserts the device_id cell of the Controller Info card is
     * populated (non-blank and not the "—" placeholder).
     *
     * `assertTextContains("")` is a no-op (satisfied by any text,
     * including the placeholder), so the assertion reads the
     * actual [SemanticsProperties.Text] and rejects the
     * placeholder explicitly.
     */
    fun assertDeviceIdNonEmpty() {
        assertCellPopulated(SettingsTestTags.ControllerInfoDeviceId, label = "device_id")
    }

    /**
     * Asserts the firmware_version cell of the Controller Info
     * card is populated. See [assertDeviceIdNonEmpty] for the
     * placeholder-rejection rationale.
     */
    fun assertFirmwareVersionNonEmpty() {
        assertCellPopulated(SettingsTestTags.ControllerInfoFirmwareVersion, label = "firmware_version")
    }

    private fun assertCellPopulated(tag: String, label: String) {
        val text = composeRule
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config
            .getOrElseNullable(SemanticsProperties.Text) { null }
            ?.joinToString("") { it.text }
            ?: error("Controller Info $label cell has no text semantics")
        require(text.isNotBlank() && text != placeholderDash) {
            "Controller Info $label is empty or placeholder: '$text'"
        }
    }

    /**
     * Asserts the protocol_version cell matches the app's
     * BuildConfig.BEET_RUNTIME_PROTOCOL_VERSION. Used by
     * FreshInstallE2ETest as a wire-version sanity check.
     */
    fun assertProtocolVersion(expected: Int) {
        composeRule
            .onNodeWithTag(SettingsTestTags.ControllerInfoProtocolVersion)
            .assertTextEquals(expected.toString())
    }

    // region watering interval

    /**
     * Sets the watering interval to [hours] h [minutes] m and saves.
     * The save button is only enabled when the field is dirty AND
     * the value passes the validator; we type into both fields
     * (which makes it dirty) and then tap the now-enabled save.
     */
    fun setWateringInterval(hours: Int, minutes: Int) {
        scrollToTag(SettingsTestTags.WateringIntervalHoursField)
        val hoursNode = composeRule.onNodeWithTag(SettingsTestTags.WateringIntervalHoursField)
        hoursNode.performScrollTo().performTextReplacement(hours.toString())
        scrollToTag(SettingsTestTags.WateringIntervalMinutesField)
        val minutesNode = composeRule.onNodeWithTag(SettingsTestTags.WateringIntervalMinutesField)
        minutesNode.performScrollTo().performTextReplacement(minutes.toString())
        scrollToTag(SettingsTestTags.WateringIntervalSave)
        composeRule
            .onNodeWithTag(SettingsTestTags.WateringIntervalSave)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
    }

    /**
     * Asserts the read-only `WateringIntervalCurrent` row shows
     * [expectedFormatted] (e.g. "1 h 0 m"). The Settings screen's
     * [SettingsTestTags.WateringIntervalCurrent] value is the
     * `formatDuration(seconds, strings)` of `state.wateringInterval.seconds`.
     */
    fun assertCurrentWateringInterval(expectedFormatted: String) {
        scrollToTag(SettingsTestTags.WateringIntervalCurrent)
        composeRule
            .onNodeWithTag(SettingsTestTags.WateringIntervalCurrent)
            .performScrollTo()
            .assertTextEquals(expectedFormatted)
    }

    // endregion

    // region max active pumps

    /**
     * Adjusts the max-active-pumps stepper draft to [target] by
     * tapping +/- buttons, then taps save. The stepper draft is
     * bounded to [1, 8] in the UI; the caller is expected to choose
     * a value in that range.
     */
    fun setMaxActivePumps(target: Int) {
        scrollToTag(SettingsTestTags.MaxActivePumpsDraftValue)
        val current = readMaxPumpsDraft()
        if (current == target) {
            // Already at target: skip the click sequence and the
            // save (the save button is gated by
            // `maxPumpsDraft != liveMax` in SettingsScreen, so
            // dirty == false means save is disabled and would
            // throw `assertIsEnabled`). The persisted value is
            // already `target`; [assertCurrentMaxActivePumps]
            // confirms the readback.
            return
        }
        scrollToTag(SettingsTestTags.MaxActivePumpsIncrement)
        val increment = composeRule.onNodeWithTag(SettingsTestTags.MaxActivePumpsIncrement)
        scrollToTag(SettingsTestTags.MaxActivePumpsDecrement)
        val decrement = composeRule.onNodeWithTag(SettingsTestTags.MaxActivePumpsDecrement)
        val targetNode = if (target > current) increment else decrement
        repeat(kotlin.math.abs(target - current)) {
            targetNode.performScrollTo().performClick()
        }
        scrollToTag(SettingsTestTags.MaxActivePumpsSave)
        composeRule
            .onNodeWithTag(SettingsTestTags.MaxActivePumpsSave)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
    }

    private fun readMaxPumpsDraft(): Int {
        val text = composeRule
            .onNodeWithTag(SettingsTestTags.MaxActivePumpsDraftValue)
            .fetchSemanticsNode()
            .config
            .getOrElseNullable(SemanticsProperties.Text) { null }
            ?.joinToString("") { it.text }
            ?: error("Stepper draft has no text semantics")
        return text.trim().toInt()
    }

    /**
     * Asserts the read-only `MaxActivePumpsCurrent` row contains
     * [expectedNumber]. The row's text is built from the
     * `settings_max_active_pumps_value` string resource which
     * typically renders as `"<n>"`.
     */
    fun assertCurrentMaxActivePumps(expectedNumber: Int) {
        scrollToTag(SettingsTestTags.MaxActivePumpsCurrent)
        composeRule
            .onNodeWithTag(SettingsTestTags.MaxActivePumpsCurrent)
            .performScrollTo()
            .assertTextContains(expectedNumber.toString())
    }

    // endregion

    // region valve config (move / settle / hold) - pull-refresh reload pattern

    private fun setValveNumberField(fieldTag: String, value: Int) {
        scrollToTag(fieldTag)
        composeRule
            .onNodeWithTag(fieldTag)
            .performScrollTo()
            .performTextReplacement(value.toString())
    }

    fun setValveMoveDuration(ms: Int) {
        setValveNumberField(SettingsTestTags.ValveConfigMoveDurationField, ms)
        scrollToTag(SettingsTestTags.ValveConfigSave)
        composeRule
            .onNodeWithTag(SettingsTestTags.ValveConfigSave)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
    }

    fun setValveSettleDelay(ms: Int) {
        setValveNumberField(SettingsTestTags.ValveConfigSettleDelayField, ms)
        scrollToTag(SettingsTestTags.ValveConfigSave)
        composeRule
            .onNodeWithTag(SettingsTestTags.ValveConfigSave)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
    }

    fun setValveOpenHold(ms: Int) {
        setValveNumberField(SettingsTestTags.ValveConfigOpenHoldField, ms)
        scrollToTag(SettingsTestTags.ValveConfigSave)
        composeRule
            .onNodeWithTag(SettingsTestTags.ValveConfigSave)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
    }

    /**
     * Pulls down on the [SettingsTestTags.Container] (the
     * BeetPullToRefreshBox) to re-fetch valveConfig + watering interval.
     *
     * The swipe is performed as a top-to-bottom gesture from inside
     * the container's bounds; the PullToRefreshBox detects the
     * downward swipe and triggers `onRefresh` which calls
     * `refreshValveConfig()` + `refreshWateringInterval()`.
     */
    fun pullToRefresh() {
        val node = composeRule.onNodeWithTag(SettingsTestTags.Container)
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val startX = (bounds.left + bounds.right) / 2f
        val startY = bounds.top + 50f
        val endX = startX
        val endY = bounds.bottom - 50f
        node.performTouchInput {
            swipe(
                start = Offset(startX, startY),
                end = Offset(endX, endY),
            )
        }
    }

    /**
     * Asserts the [SettingsTestTags.ValveConfigMoveDurationField]
     * text equals [expectedMillisStr] (the field repopulated by
     * `LaunchedEffect(valveConfig)` after a successful pull-to-refresh).
     */
    fun assertValveMoveDuration(expectedMillisStr: String) {
        scrollToTag(SettingsTestTags.ValveConfigMoveDurationField)
        composeRule
            .onNodeWithTag(SettingsTestTags.ValveConfigMoveDurationField)
            .assertTextEquals(expectedMillisStr)
    }

    fun assertValveSettleDelay(expectedMillisStr: String) {
        scrollToTag(SettingsTestTags.ValveConfigSettleDelayField)
        composeRule
            .onNodeWithTag(SettingsTestTags.ValveConfigSettleDelayField)
            .assertTextEquals(expectedMillisStr)
    }

    fun assertValveOpenHold(expectedMillisStr: String) {
        scrollToTag(SettingsTestTags.ValveConfigOpenHoldField)
        composeRule
            .onNodeWithTag(SettingsTestTags.ValveConfigOpenHoldField)
            .assertTextEquals(expectedMillisStr)
    }

    // endregion

    // region valve enabled toggle

    /**
     * Sets the Valve Config card's `valve_enabled` switch to [enabled]
     * by clicking the switch until the desired state is reached. The
     * switch's checked state isn't tagged, so we click until the
     * read-only `ValveEnabledValue` row on the Valve card shows
     * "Yes" / "No" as expected.
     */
    fun setValveEnabled(enabled: Boolean) {
        scrollToTag(SettingsTestTags.ValveEnabledValue)
        val currentLabel = composeRule
            .onNodeWithTag(SettingsTestTags.ValveEnabledValue)
            .fetchSemanticsNode()
            .config
            .getOrElseNullable(SemanticsProperties.Text) { null }
            ?.joinToString("") { it.text }
            ?: ""
        val currentYes = currentLabel == yesLabel
        if (currentYes != enabled) {
            scrollToTag(SettingsTestTags.ValveConfigEnabledSwitch)
            composeRule
                .onNodeWithTag(SettingsTestTags.ValveConfigEnabledSwitch)
                .performScrollTo()
                .performClick()
        }
    }

    /**
     * Asserts the [SettingsTestTags.ValveEnabledValue] cell shows
     * the localized "Yes" / "No" corresponding to [enabled].
     * Strings are resolved from `R.string.common_yes` /
     * `common_no` at robot init so the device locale is respected.
     */
    fun assertValveEnabled(enabled: Boolean) {
        scrollToTag(SettingsTestTags.ValveEnabledValue)
        composeRule
            .onNodeWithTag(SettingsTestTags.ValveEnabledValue)
            .assertTextEquals(if (enabled) yesLabel else noLabel)
    }

    // endregion

    // region firmware update entry point

    /**
     * Opens the Maintenance screen by tapping the firmware-update
     * card's open button on the Settings screen. Returns a
     * [FirmwareUpdateRobot] bound to the same Compose rule for the
     * rest of the firmware-update flow (use bundled, install, etc.).
     */
    fun openFirmwareUpdate(): FirmwareUpdateRobot {
        scrollToTag(SettingsTestTags.FirmwareUpdateOpenButton)
        composeRule
            .onNodeWithTag(SettingsTestTags.FirmwareUpdateOpenButton)
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithTag(MaintenanceUpdateTestTags.Card)
            .assertIsDisplayed()
        return FirmwareUpdateRobot(composeRule)
    }

    // endregion
}

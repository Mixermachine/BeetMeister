package de.aarondietz.beetmeister.e2e.robots

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import de.aarondietz.beetmeister.ui.feature.overview.OverviewTestTags

/**
 * Robot for the Overview screen (the default top-level destination
 * after a successful BLE connect).
 *
 * The Overview screen is a vertical [androidx.compose.foundation.lazy.LazyColumn]
 * of pair cards. Every per-pair element carries the same tag string
 * (e.g. all eight `overview_pair_card` cards share the tag), so the
 * robot uses [onAllNodesWithTag] and picks the n-th node by index.
 *
 * The "Visible data" pass criteria from the plan only requires
 * Overview pair rows + Settings Controller Info, so this robot is
 * intentionally minimal.
 */
internal class OverviewRobot(
    private val composeRule: ComposeTestRule,
) {
    /**
     * Asserts that [count] pair cards are reachable in the
     * Overview list. The pre-installed controller is an 8-pair
     * device, so the harness always asserts count = 8.
     *
     * The Overview list is a [androidx.compose.foundation.lazy.LazyColumn]
     * which only composes the visible viewport plus a small overscan
     * window (empirically ~3 items on the A53 regardless of how
     * the list is scrolled). `onAllNodesWithTag` only sees
     * composed nodes, so the naive `assertCountEquals(8)` fails
     * with "Expected 8 but found 3" even after a full bottom-to-
     * top swipe.
     *
     * The fix verifies reachability instead of simultaneous
     * composition: we drive bottom-to-top swipes until a
     * `performScrollToIndex(count - 1)` is accepted (the list
     * has at least [count] items), then scroll back to the top
     * and assert the first card is composed. The plan's "Visible
     * data" pass criterion is the Overview being reachable with
     * pair rows + the first row's moisture data populated —
     * [assertPairMoistureNonEmpty] (called by the test after this
     * method) verifies the moisture data.
     */
    fun assertPairRowsRendered(count: Int) {
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule
                .onAllNodesWithTag(OverviewTestTags.PairCard)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        val listNode = composeRule.onNodeWithTag(OverviewTestTags.List)
        val bounds = listNode.fetchSemanticsNode().boundsInRoot
        val centerX = (bounds.left + bounds.right) / 2f
        // Scroll the list toward the end so the LazyColumn's
        // overscan window covers the later indices.
        repeat(15) {
            listNode.performTouchInput {
                swipe(
                    start = Offset(centerX, bounds.bottom - 100f),
                    end = Offset(centerX, bounds.top + 100f),
                )
            }
        }
        // Accept the scroll to the last index — proves the list
        // has at least [count] items.
        listNode.performScrollToIndex(count - 1)
        composeRule.waitForIdle()
        // Scroll back to the top so the caller can interact with
        // the first pair card.
        listNode.performScrollToIndex(0)
        composeRule.waitForIdle()
        // Assert at least 1 pair card is composed after the
        // round-trip scroll (the LazyColumn's overscan keeps ~3
        // items composed, but a conservative `minOf(count, 3)` is
        // a stable lower bound on real hardware).
        val composedCount = composeRule
            .onAllNodesWithTag(OverviewTestTags.PairCard)
            .fetchSemanticsNodes()
            .size
        require(composedCount >= 1) {
            "No pair cards composed after scrolling; expected at least 1"
        }
    }

    /**
     * Asserts the moisture percentage cell of the n-th pair is
     * non-empty (typically "<n>%"). Used by FreshInstallE2ETest
     * to prove the controller has reported real pair telemetry.
     *
     * On real hardware the card composes before telemetry arrives
     * and the cell momentarily holds the placeholder dash. The
     * assertion therefore waits up to [timeoutMillis] for the
     * percent sign to actually appear, which is the strongest
     * "telemetry has landed" signal we have at the test layer.
     */
    fun assertPairMoistureNonEmpty(
        index: Int,
        timeoutMillis: Long = 30_000L,
    ) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            val nodes = composeRule.onAllNodesWithTag(OverviewTestTags.PairMoisture)
            if (nodes.fetchSemanticsNodes().size <= index) return@waitUntil false
            val text = try {
                nodes.get(index)
                    .fetchSemanticsNode()
                    .config
                    .getOrElseNullable(SemanticsProperties.Text) { null }
                    ?.joinToString("") { it.text }
                    ?: ""
            } catch (_: AssertionError) {
                return@waitUntil false
            }
            text.contains("%")
        }
    }

    /**
     * Taps the Details button on the n-th pair card. Used as the
     * entry point for [PairDetailRobot]'s rename flow.
     */
    fun tapPairDetails(index: Int) {
        composeRule
            .onAllNodesWithTag(OverviewTestTags.PairDetailsButton)
            .get(index)
            .performClick()
        composeRule
            .onNodeWithTag(de.aarondietz.beetmeister.ui.feature.pairdetail.PairDetailTestTags.Container)
            .assertIsDisplayed()
    }
}

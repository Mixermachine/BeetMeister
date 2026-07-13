package de.aarondietz.beetmeister.e2e.robots

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
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
     * Asserts that [count] pair cards are rendered. The pre-installed
     * controller is an 8-pair device, so the harness always asserts
     * count = 8.
     *
     * The Overview list is a [androidx.compose.foundation.lazy.LazyColumn]
     * which only composes the visible viewport plus a small overscan
     * window. On the A53's 1080x2400 screen, only the first 2 pair
     * cards fit below the system-values card; pairs 3..8 are not
     * composed until the list is scrolled. `onAllNodesWithTag` only
     * sees composed nodes, so the naive `assertCountEquals(count)`
     * fails with "Expected 8 but found 2".
     *
     * The fix scrolls the list to each expected index via
     * `performScrollToIndex` until either all [count] cards have
     * been composed at least once (we have a stable total of
     * [count]) or the `assertCountEquals` on the final pass
     * confirms it. A 20-iteration cap protects against infinite
     * loops if the list ever fails to grow.
     */
    fun assertPairRowsRendered(count: Int) {
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule
                .onAllNodesWithTag(OverviewTestTags.PairCard)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        val listNode = composeRule.onNodeWithTag(OverviewTestTags.List)
        var composedCount = 0
        repeat(20) {
            composedCount = composeRule
                .onAllNodesWithTag(OverviewTestTags.PairCard)
                .fetchSemanticsNodes()
                .size
            if (composedCount >= count) return@repeat
            listNode.performScrollToIndex(composedCount)
        }
        composeRule
            .onAllNodesWithTag(OverviewTestTags.PairCard)
            .assertCountEquals(count)
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

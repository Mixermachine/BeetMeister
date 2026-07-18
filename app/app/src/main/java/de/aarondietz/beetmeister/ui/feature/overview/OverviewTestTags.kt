package de.aarondietz.beetmeister.ui.feature.overview

/**
 * Stable Compose test tags for [OverviewScreen].
 *
 * Convention: every tag is applied to the stable outer element of the UI it
 * identifies (e.g. the per-pair ElevatedCard, the moisture ValueGridRow) so the
 * E2E test robot can find nodes via [androidx.compose.ui.test.onNodeWithTag] /
 * [androidx.compose.ui.test.onAllNodesWithTag] without depending on text content
 * or nested spans.
 *
 * Per-pair tags ([PairCard], [PairMoisture], [PairSensor], [PairState]) are
 * applied to every pair; the E2E test selects a specific index via
 * `onAllNodesWithTag(...)[index]`.
 */
internal object OverviewTestTags {
    const val List = "overview_list"
    const val SystemValuesCard = "overview_system_values_card"
    const val PairCard = "overview_pair_card"
    const val PairName = "overview_pair_name"
    const val PairState = "overview_pair_state"
    const val PairMoisture = "overview_pair_moisture"
    const val PairSensor = "overview_pair_sensor"
    const val PairDetailsButton = "overview_pair_details_button"
    const val PairEnableToggle = "overview_pair_enable_toggle"
    const val PairClearErrorButton = "overview_pair_clear_error_button"
}

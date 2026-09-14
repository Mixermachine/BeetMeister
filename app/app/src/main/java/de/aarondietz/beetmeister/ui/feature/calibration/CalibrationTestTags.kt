package de.aarondietz.beetmeister.ui.feature.calibration

/**
 * Stable Compose test tags for [CalibrationScreen].
 *
 * Tags are indexed by pair number so instrumented tests can target a
 * specific pair card, its editable dry/wet reference fields, its title
 * (pair name), and its capture/save actions.
 */
internal object CalibrationTestTags {
    fun card(pairIndex: Int) = "calibration_card_$pairIndex"
    fun title(pairIndex: Int) = "calibration_title_$pairIndex"
    fun dryInput(pairIndex: Int) = "calibration_dry_input_$pairIndex"
    fun wetInput(pairIndex: Int) = "calibration_wet_input_$pairIndex"
    fun captureDryButton(pairIndex: Int) = "calibration_capture_dry_button_$pairIndex"
    fun captureWetButton(pairIndex: Int) = "calibration_capture_wet_button_$pairIndex"
    fun saveButton(pairIndex: Int) = "calibration_save_button_$pairIndex"
}

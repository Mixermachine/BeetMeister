package de.aarondietz.beetmeister.ui.feature.pairdetail

/**
 * Stable Compose test tags for [PairDetailScreen] and its rename dialog.
 *
 * Used by SettingsUpdateE2ETest for the pair_name_rename case
 * (Overview -> tap pair -> Details -> rename IconButton -> dialog text ->
 * Save) and by FreshInstallE2ETest to land on a pair details screen.
 */
internal object PairDetailTestTags {
    const val Container = "pair_detail_container"
    const val BackButton = "pair_detail_back_button"
    const val Name = "pair_detail_name"
    const val RenameButton = "pair_detail_rename_button"
    const val RenameDialog = "pair_detail_rename_dialog"
    const val RenameDialogTextField = "pair_detail_rename_dialog_text_field"
    const val RenameDialogSave = "pair_detail_rename_dialog_save"
    const val RenameDialogCancel = "pair_detail_rename_dialog_cancel"
    const val EnabledToggle = "pair_detail_enabled_toggle"
    const val TargetLevelDry = "pair_detail_target_level_dry"
    const val TargetLevelMedium = "pair_detail_target_level_medium"
    const val TargetLevelMoist = "pair_detail_target_level_moist"
    const val MultiplierSlider = "pair_detail_multiplier_slider"
    const val SaveConfigButton = "pair_detail_save_config_button"
}

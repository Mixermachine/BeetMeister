package de.aarondietz.beetmeister.ui.feature.settings

/**
 * Stable Compose test tags for [SettingsScreen].
 *
 * The Settings screen is the surface for the SettingsUpdateE2ETest
 * (Phase 2). Per the harness plan, every writable setting must have
 * a stable, indexable readback target. For settings that already
 * expose a read-only "current value" row, that row carries the
 * `*Current` tag; for the three valve-numeric settings
 * (move/settle/hold) which have no read-only row, the
 * SettingsUpdateE2ETest uses the pull-refresh reload pattern against
 * the editable [ValveConfigMoveDurationField] / [ValveConfigSettleDelayField]
 * / [ValveConfigOpenHoldField] field tags.
 *
 * [Container] tags the BeetPullToRefreshBox so the robot can target
 * the pull-to-refresh gesture for the reload pattern.
 */
internal object SettingsTestTags {
    const val Container = "settings_container"
    const val List = "settings_list"
    const val ControllerInfoCard = "settings_controller_info_card"
    const val ControllerInfoDeviceId = "settings_controller_info_device_id"
    const val ControllerInfoFirmwareVersion = "settings_controller_info_firmware_version"
    const val ControllerInfoProtocolVersion = "settings_controller_info_protocol_version"
    const val ControllerInfoBuildLabel = "settings_controller_info_build_label"
    const val ControllerInfoPairCount = "settings_controller_info_pair_count"
    const val ControllerInfoConnectionPhase = "settings_controller_info_connection_phase"
    const val ControllerInfoAddress = "settings_controller_info_address"
    const val ControllerInfoDisconnect = "settings_controller_info_disconnect"
    const val FirmwareUpdateCard = "settings_firmware_update_card"
    const val FirmwareUpdateOpenButton = "settings_firmware_update_open_button"
    const val ControllerManagementCard = "settings_controller_management_card"
    const val ControllerManagementReboot = "settings_controller_management_reboot"
    const val ControllerManagementFactoryReset = "settings_controller_management_factory_reset"
    const val WateringIntervalCard = "settings_watering_interval_card"
    const val WateringIntervalCurrent = "settings_watering_interval_current"
    const val WateringIntervalNextCheck = "settings_watering_interval_next_check"
    const val WateringIntervalHoursField = "settings_watering_interval_hours_field"
    const val WateringIntervalMinutesField = "settings_watering_interval_minutes_field"
    const val WateringIntervalSave = "settings_watering_interval_save"
    const val WateringIntervalRunScheduler = "settings_watering_interval_run_scheduler"
    const val ValveCard = "settings_valve_card"
    const val ValveStateValue = "settings_valve_state_value"
    const val ValveEnabledValue = "settings_valve_enabled_value"
    const val ValveOpenButton = "settings_valve_open_button"
    const val ValveCloseButton = "settings_valve_close_button"
    const val ValveCalibrationButton = "settings_valve_calibration_button"
    const val ValveConfigCard = "settings_valve_config_card"
    const val ValveConfigEnabledSwitch = "settings_valve_config_enabled_switch"
    const val ValveConfigMoveDurationField = "settings_valve_config_move_duration_field"
    const val ValveConfigSettleDelayField = "settings_valve_config_settle_delay_field"
    const val ValveConfigOpenHoldField = "settings_valve_config_open_hold_field"
    const val ValveConfigSave = "settings_valve_config_save"
    const val MaxActivePumpsCard = "settings_max_active_pumps_card"
    const val MaxActivePumpsCurrent = "settings_max_active_pumps_current"
    const val MaxActivePumpsDecrement = "settings_max_active_pumps_decrement"
    const val MaxActivePumpsIncrement = "settings_max_active_pumps_increment"
    const val MaxActivePumpsDraftValue = "settings_max_active_pumps_draft_value"
    const val MaxActivePumpsSave = "settings_max_active_pumps_save"
}

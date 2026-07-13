package de.aarondietz.beetmeister.ui

import de.aarondietz.beetmeister.ui.core.app.TopLevelScreen

/**
 * Stable Compose test tags for the four `NavigationSuiteScaffold` nav items
 * hosted in [BeetMeisterApp].
 *
 * These replace the fragile `onNodeWithText("Settings")` pattern that
 * double-matches the Settings screen title once the user is on the
 * Settings screen. Robots in Phase 2 use the nav-item tags to navigate
 * between Overview / Calibration / Events / Settings deterministically.
 */
internal object NavigationSuiteTestTags {
    const val OverviewNavItem = "nav_overview_item"
    const val CalibrationNavItem = "nav_calibration_item"
    const val EventsNavItem = "nav_events_item"
    const val SettingsNavItem = "nav_settings_item"

    /**
     * Returns the nav-item test tag for a given destination. Used by
     * [BeetMeisterApp] to apply `Modifier.testTag(...)` to each nav item's
     * label.
     */
    fun tagFor(destination: TopLevelScreen): String = when (destination) {
        TopLevelScreen.Overview -> OverviewNavItem
        TopLevelScreen.Calibration -> CalibrationNavItem
        TopLevelScreen.Events -> EventsNavItem
        TopLevelScreen.Settings -> SettingsNavItem
    }
}

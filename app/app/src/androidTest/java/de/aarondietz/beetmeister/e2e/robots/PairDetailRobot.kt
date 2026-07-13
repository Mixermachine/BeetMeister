package de.aarondietz.beetmeister.e2e.robots

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import de.aarondietz.beetmeister.ui.feature.pairdetail.PairDetailTestTags

/**
 * Robot for the PairDetail screen (reached from Overview via
 * `PairDetailsButton`).
 *
 * Currently scoped to the rename flow because that is the only
 * pair-detail interaction the E2E settings-update suite needs.
 * Other pair-detail actions (manual start/stop, moisture test,
 * clear error, enable toggle) are out of scope for the harness.
 */
internal class PairDetailRobot(
    private val composeRule: ComposeTestRule,
) {
    /**
     * Opens the rename dialog, types [newName] into the text field,
     * and taps Save. The caller is responsible for having already
     * landed on the pair-detail screen for the intended pair (via
     * [OverviewRobot.tapPairDetails]).
     *
     * The pair-detail screen's [PairDetailTestTags.RenameDialog]
     * is an `AlertDialog` modifier, so once the dialog is open
     * the robot targets its save / cancel / text-field tags which
     * are scoped to the dialog.
     */
    fun renameTo(newName: String) {
        composeRule.onNodeWithTag(PairDetailTestTags.RenameButton).performClick()
        composeRule
            .onNodeWithTag(PairDetailTestTags.RenameDialogTextField)
            .performTextReplacement(newName)
        composeRule.onNodeWithTag(PairDetailTestTags.RenameDialogSave).performClick()
    }

    /**
     * Asserts the pair's name (displayed as the headline) equals
     * [expected]. Used by the settings-update suite to verify the
     * rename round-trip after [renameTo].
     */
    fun assertNameEquals(expected: String) {
        composeRule.onNodeWithTag(PairDetailTestTags.Name).assertTextEquals(expected)
    }

    /** Navigates back to the Overview (via the in-screen back button). */
    fun back() {
        composeRule.onNodeWithTag(PairDetailTestTags.BackButton).performClick()
    }
}

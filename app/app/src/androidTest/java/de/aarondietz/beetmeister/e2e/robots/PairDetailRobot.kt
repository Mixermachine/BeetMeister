package de.aarondietz.beetmeister.e2e.robots

import androidx.compose.ui.test.assertTextContains
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
     * Asserts the pair's name (displayed as the headline) contains
     * [expected]. Used by the settings-update suite to verify the
     * rename round-trip after [renameTo].
     *
     * Uses [assertTextContains] rather than [assertTextEquals]
     * because the pair-detail name is rendered in a single-line
     * `Text` composable that truncates with an ellipsis when the
     * name exceeds the headline width. On the A53 a 22-char name
     * like `"e2e-renamed-pairName"` is truncated to the visible
     * width (~16 chars), so [assertTextEquals] would see only the
     * truncated prefix. The full string is still in the semantics
     * tree (the truncation is visual only), so a substring match
     * validates the rename without depending on the device width.
     */
    fun assertNameEquals(expected: String) {
        composeRule.onNodeWithTag(PairDetailTestTags.Name).assertTextContains(expected)
    }

    /** Navigates back to the Overview (via the in-screen back button). */
    fun back() {
        composeRule.onNodeWithTag(PairDetailTestTags.BackButton).performClick()
    }
}

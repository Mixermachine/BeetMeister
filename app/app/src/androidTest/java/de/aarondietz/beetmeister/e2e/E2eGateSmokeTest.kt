package de.aarondietz.beetmeister.e2e

import androidx.compose.ui.test.junit4.createComposeRule
import de.aarondietz.beetmeister.ui.feature.connection.MaintenanceUpdateTestTags
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import de.aarondietz.beetmeister.ui.feature.connection.MaintenanceScreen
import de.aarondietz.beetmeister.model.repository.BeetRepositoryState
import org.junit.Rule
import org.junit.Test

/**
 * Self-test for the [E2e] gate installed by [BeetE2eAwareJUnitRunner].
 *
 * This is a **manual gate verification**, not part of the harness
 * suite. The orchestrator's real dispatch uses
 * `-e package de.aarondietz.beetmeister.e2e -e class ...<SuiteE2ETest>`,
 * so this class is not auto-run during an orchestrator pipeline.
 *
 * To verify the gate manually on a connected device:
 *  - `am instrument -e class E2eGateSmokeTest ...runner...` (no
 *    `beetRunE2e`) -> every test in the class is filtered out by
 *    [E2eSkipFilter] and JUnit reports `NoTestsRemainException` for
 *    the class init ("Tests run: 1, Failures: 1").
 *  - `am instrument -e beetRunE2e true -e class E2eGateSmokeTest
 *    ...runner...` -> the test runs to completion ("OK (1 test)").
 *
 * Recorded once in `artifacts/p1-evidence/smoke-gate.txt` during the
 * Phase 1 exit review; rerun by hand after any change to
 * [BeetE2eAwareJUnitRunner] or [E2eSkipFilter].
 */
@E2e
class E2eGateSmokeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun gateMarker_runsOnlyWhenBeetRunE2eIsTrue() {
        composeRule.setContent {
            MaintenanceScreen(
                state = BeetRepositoryState(),
                forcedMode = true,
                onClose = null,
                onPrepareBundledFirmware = {},
                onPickCustomFirmware = {},
                onStartMaintenanceUpdate = {},
                onAbortMaintenanceUpdate = {},
                onDisconnect = {},
            )
        }
        composeRule.onNodeWithTag(MaintenanceUpdateTestTags.Card).assertIsDisplayed()
    }
}

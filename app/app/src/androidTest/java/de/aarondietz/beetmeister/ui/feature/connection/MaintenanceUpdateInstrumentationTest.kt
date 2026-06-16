package de.aarondietz.beetmeister.ui.feature.connection

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import de.aarondietz.beetmeister.MainActivity
import de.aarondietz.beetmeister.model.connection.BeetConnectionPhase
import de.aarondietz.beetmeister.model.connection.BeetConnectionState
import de.aarondietz.beetmeister.model.controller.BeetMaintenanceInfo
import de.aarondietz.beetmeister.model.repository.BeetRepositoryState
import de.aarondietz.beetmeister.model.update.BeetFirmwareMetadata
import de.aarondietz.beetmeister.model.update.BeetFirmwarePackageSummary
import de.aarondietz.beetmeister.model.update.BeetFirmwareSource
import de.aarondietz.beetmeister.model.update.BeetMaintenanceUpdatePhase
import de.aarondietz.beetmeister.model.update.BeetMaintenanceUpdateState
import org.junit.Rule
import org.junit.Test

class MaintenanceUpdateInstrumentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun maintenanceRequiredWithoutSelectionShowsActionsAndDisabledInstall() {
        composeRule.setContent {
            ConnectionGate(
                state = baseState(),
                permissionsPermanentlyDenied = false,
                onRequestPermissions = {},
                onRequestBluetooth = {},
                onScan = {},
                onConnect = {},
                onDisconnect = {},
                onPrepareBundledFirmware = {},
                onPickCustomFirmware = {},
                onStartMaintenanceUpdate = {},
                onAbortMaintenanceUpdate = {},
            )
        }

        composeRule.onNodeWithTag(MaintenanceUpdateTestTags.Card).assertIsDisplayed()
        composeRule.onNodeWithTag(MaintenanceUpdateTestTags.Title).assertIsDisplayed()
        composeRule.onNodeWithTag(MaintenanceUpdateTestTags.CurrentFirmware).assertIsDisplayed()
        composeRule.onNodeWithTag(MaintenanceUpdateTestTags.BundledButton).assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithTag(MaintenanceUpdateTestTags.CustomButton).assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithTag(MaintenanceUpdateTestTags.InstallButton).assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithTag(MaintenanceUpdateTestTags.DisconnectButton).assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun selectedCustomFirmwareShowsSummaryWarningsAndEnabledInstall() {
        composeRule.setContent {
            ConnectionGate(
                state = baseState().copy(
                    maintenanceUpdate = BeetMaintenanceUpdateState(
                        selectedFirmware = customSelection(isDowngrade = true, runtimeWarning = true),
                        phase = BeetMaintenanceUpdatePhase.Ready,
                        statusDetail = "Ready to install custom build",
                    ),
                ),
                permissionsPermanentlyDenied = false,
                onRequestPermissions = {},
                onRequestBluetooth = {},
                onScan = {},
                onConnect = {},
                onDisconnect = {},
                onPrepareBundledFirmware = {},
                onPickCustomFirmware = {},
                onStartMaintenanceUpdate = {},
                onAbortMaintenanceUpdate = {},
            )
        }

        composeRule.onNodeWithTag(MaintenanceUpdateTestTags.Summary).assertIsDisplayed()
        composeRule.onNodeWithTag(MaintenanceUpdateTestTags.Details).assertIsDisplayed()
        composeRule.onNodeWithTag(MaintenanceUpdateTestTags.DowngradeWarning).assertIsDisplayed()
        composeRule.onNodeWithTag(MaintenanceUpdateTestTags.RuntimeWarning).assertIsDisplayed()
        composeRule.onNodeWithTag(MaintenanceUpdateTestTags.StatusDetail).assertIsDisplayed()
        composeRule.onNodeWithTag(MaintenanceUpdateTestTags.InstallButton).assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun uploadingPhaseShowsProgressAndAbortInsteadOfInstall() {
        composeRule.setContent {
            ConnectionGate(
                state = baseState().copy(
                    maintenanceUpdate = BeetMaintenanceUpdateState(
                        selectedFirmware = customSelection(isDowngrade = false, runtimeWarning = false),
                        phase = BeetMaintenanceUpdatePhase.Uploading,
                        statusDetail = "Uploading 512 / 1024 bytes",
                    ),
                ),
                permissionsPermanentlyDenied = false,
                onRequestPermissions = {},
                onRequestBluetooth = {},
                onScan = {},
                onConnect = {},
                onDisconnect = {},
                onPrepareBundledFirmware = {},
                onPickCustomFirmware = {},
                onStartMaintenanceUpdate = {},
                onAbortMaintenanceUpdate = {},
            )
        }

        composeRule.onNodeWithTag(MaintenanceUpdateTestTags.Progress).assertIsDisplayed()
        composeRule.onNodeWithTag(MaintenanceUpdateTestTags.AbortButton).assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithTag(MaintenanceUpdateTestTags.StatusDetail).assertIsDisplayed()
        composeRule.onAllNodesWithTag(MaintenanceUpdateTestTags.InstallButton).assertCountEquals(0)
    }

    private fun baseState(): BeetRepositoryState =
        BeetRepositoryState(
            connection = BeetConnectionState(
                phase = BeetConnectionPhase.MaintenanceRequired,
                detail = "Firmware update required before normal use.",
            ),
            maintenanceInfo = BeetMaintenanceInfo(
                productId = "beetmeister",
                hardwareRev = "rev_a",
                firmwareVersion = "0.2.0",
                buildLabel = "v0.2.0",
                maintenanceProtocolVersion = 1,
                runtimeProtocolVersion = 8,
                updateCapable = true,
                imageKind = "bundled",
            ),
        )

    private fun customSelection(
        isDowngrade: Boolean,
        runtimeWarning: Boolean,
    ): BeetFirmwarePackageSummary =
        BeetFirmwarePackageSummary(
            source = BeetFirmwareSource.Custom,
            sourceLabel = "custom-abc1234.bin",
            assetId = "custom-abc1234.bin",
            metadata = BeetFirmwareMetadata(
                productId = "beetmeister",
                hardwareRev = "rev_a",
                firmwareVersion = if (isDowngrade) "0.1.0" else "0.3.0",
                buildLabel = if (isDowngrade) "custom-abc1234" else "v0.3.0+abc1234",
                maintenanceProtocolVersion = 1,
                runtimeProtocolVersion = if (runtimeWarning) 7 else 8,
                imageKind = "custom",
                compatibleHardwareRevs = listOf("rev_a"),
            ),
            imageSize = 1024,
            sha256Hex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            isDowngrade = isDowngrade,
            runtimeProtocolWarning = runtimeWarning,
        )
}

class MaintenanceUpdateLiveActivityInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun liveActivityCanOpenBundledFirmwareFlow() {
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodes(hasText("Settings")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodes(hasTestTag(MaintenanceUpdateTestTags.BundledButton)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(MaintenanceUpdateTestTags.BundledButton).performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodes(hasTestTag(MaintenanceUpdateTestTags.Summary)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(MaintenanceUpdateTestTags.InstallButton).performScrollTo().assertIsDisplayed().performClick()
    }
}

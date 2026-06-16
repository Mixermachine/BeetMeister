package de.aarondietz.beetmeister.data.firmware

import de.aarondietz.beetmeister.model.controller.BeetMaintenanceInfo
import de.aarondietz.beetmeister.model.update.BeetFirmwareMetadata
import de.aarondietz.beetmeister.model.update.BeetFirmwareSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeetFirmwareCatalogTest {
    @Test
    fun summarizePackageFlagsDowngradeAndRuntimeWarning() {
        val imagePackage = BeetFirmwareImagePackage(
            metadata = BeetFirmwareMetadata(
                productId = "beetmeister",
                hardwareRev = "rev_a",
                firmwareVersion = "v0.1.0",
                buildLabel = "v0.1.0",
                maintenanceProtocolVersion = 1,
                runtimeProtocolVersion = 8,
                imageKind = "bundled",
                compatibleHardwareRevs = listOf("rev_a"),
            ),
            imageBytes = ByteArray(16),
            imageSize = 16,
            sha256Hex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        )

        val summary = BeetFirmwareCatalog.summarizePackage(
            imagePackage = imagePackage,
            source = BeetFirmwareSource.Bundled,
            sourceLabel = "Bundled",
            assetId = "firmware/test.bin",
            maintenanceInfo = BeetMaintenanceInfo(
                productId = "beetmeister",
                hardwareRev = "rev_a",
                firmwareVersion = "v0.2.0",
                buildLabel = "v0.2.0",
                maintenanceProtocolVersion = 1,
                runtimeProtocolVersion = 9,
                updateCapable = true,
                imageKind = "bundled",
            ),
            supportedRuntimeProtocolVersion = 9,
        )

        assertTrue(summary.isDowngrade)
        assertTrue(summary.runtimeProtocolWarning)
    }

    @Test
    fun summarizePackageLeavesCurrentCompatibleImageUnflagged() {
        val imagePackage = BeetFirmwareImagePackage(
            metadata = BeetFirmwareMetadata(
                productId = "beetmeister",
                hardwareRev = "rev_a",
                firmwareVersion = "v0.2.0",
                buildLabel = "v0.2.0",
                maintenanceProtocolVersion = 1,
                runtimeProtocolVersion = 9,
                imageKind = "custom",
                compatibleHardwareRevs = listOf("rev_a"),
            ),
            imageBytes = ByteArray(16),
            imageSize = 16,
            sha256Hex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        )

        val summary = BeetFirmwareCatalog.summarizePackage(
            imagePackage = imagePackage,
            source = BeetFirmwareSource.Custom,
            sourceLabel = "my-build.bin",
            assetId = "my-build.bin",
            maintenanceInfo = BeetMaintenanceInfo(
                productId = "beetmeister",
                hardwareRev = "rev_a",
                firmwareVersion = "v0.2.0",
                buildLabel = "v0.2.0",
                maintenanceProtocolVersion = 1,
                runtimeProtocolVersion = 9,
                updateCapable = true,
                imageKind = "bundled",
            ),
            supportedRuntimeProtocolVersion = 9,
        )

        assertFalse(summary.isDowngrade)
        assertFalse(summary.runtimeProtocolWarning)
    }
}

package de.aarondietz.beetmeister.data.firmware

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import de.aarondietz.beetmeister.model.controller.BeetMaintenanceInfo
import de.aarondietz.beetmeister.model.update.BeetFirmwarePackageSummary
import de.aarondietz.beetmeister.model.update.BeetFirmwareSource

internal object BeetFirmwareCatalog {
    private const val BUNDLED_ASSET_PATH = "firmware/beetmeister-rev_a-bundled.bin"
    private const val CUSTOM_BLE_ASSET_ID = "custom"
    private const val MAX_CUSTOM_IMAGE_BYTES = 4 * 1024 * 1024

    fun loadBundledFirmware(
        context: Context,
        maintenanceInfo: BeetMaintenanceInfo?,
        supportedRuntimeProtocolVersion: Int,
    ): Pair<BeetFirmwareImagePackage, BeetFirmwarePackageSummary> {
        val imageBytes = context.assets.open(BUNDLED_ASSET_PATH).use { it.readBytes() }
        val imagePackage = BeetFirmwareImageParser.parseImage(imageBytes)
        val assetId = buildBundledAssetId(imagePackage)
        return imagePackage to imagePackage.toSummary(
            source = BeetFirmwareSource.Bundled,
            sourceLabel = "Bundled",
            assetId = assetId,
            maintenanceInfo = maintenanceInfo,
            supportedRuntimeProtocolVersion = supportedRuntimeProtocolVersion,
        )
    }

    fun loadCustomFirmware(
        contentResolver: ContentResolver,
        uri: Uri,
        maintenanceInfo: BeetMaintenanceInfo?,
        supportedRuntimeProtocolVersion: Int,
    ): Pair<BeetFirmwareImagePackage, BeetFirmwarePackageSummary> {
        val imageBytes = contentResolver.openInputStream(uri)?.use { stream -> stream.readBytes() }
            ?: error("Could not read selected firmware image.")
        check(imageBytes.size in 1..MAX_CUSTOM_IMAGE_BYTES) { "Selected firmware image is empty or too large." }
        val imagePackage = BeetFirmwareImageParser.parseImage(imageBytes)
        val label = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "custom image"
        return imagePackage to imagePackage.toSummary(
            source = BeetFirmwareSource.Custom,
            sourceLabel = label,
            assetId = CUSTOM_BLE_ASSET_ID,
            maintenanceInfo = maintenanceInfo,
            supportedRuntimeProtocolVersion = supportedRuntimeProtocolVersion,
        )
    }

    internal fun summarizePackage(
        imagePackage: BeetFirmwareImagePackage,
        source: BeetFirmwareSource,
        sourceLabel: String,
        assetId: String,
        maintenanceInfo: BeetMaintenanceInfo?,
        supportedRuntimeProtocolVersion: Int,
    ): BeetFirmwarePackageSummary = imagePackage.toSummary(
        source = source,
        sourceLabel = sourceLabel,
        assetId = assetId,
        maintenanceInfo = maintenanceInfo,
        supportedRuntimeProtocolVersion = supportedRuntimeProtocolVersion,
    )

    internal fun matchesInstalledFirmware(
        selectedFirmware: BeetFirmwarePackageSummary,
        maintenanceInfo: BeetMaintenanceInfo?,
    ): Boolean {
        val info = maintenanceInfo ?: return false
        val metadata = selectedFirmware.metadata
        return info.productId == metadata.productId &&
            info.firmwareVersion == metadata.firmwareVersion &&
            info.runtimeProtocolVersion == metadata.runtimeProtocolVersion &&
            info.imageKind == metadata.imageKind &&
            metadata.compatibleHardwareRevs.contains(info.hardwareRev)
    }

    private fun BeetFirmwareImagePackage.toSummary(
        source: BeetFirmwareSource,
        sourceLabel: String,
        assetId: String,
        maintenanceInfo: BeetMaintenanceInfo?,
        supportedRuntimeProtocolVersion: Int,
    ): BeetFirmwarePackageSummary {
        val currentVersion = maintenanceInfo?.firmwareVersion
        return BeetFirmwarePackageSummary(
            source = source,
            sourceLabel = sourceLabel,
            assetId = assetId,
            metadata = metadata,
            imageSize = imageSize,
            sha256Hex = sha256Hex,
            isDowngrade = currentVersion != null && compareVersions(metadata.firmwareVersion, currentVersion) < 0,
            runtimeProtocolWarning = metadata.runtimeProtocolVersion != supportedRuntimeProtocolVersion,
        )
    }

    private fun buildBundledAssetId(imagePackage: BeetFirmwareImagePackage): String {
        val metadata = imagePackage.metadata
        val raw = "bundled-${metadata.hardwareRev}-${metadata.firmwareVersion}"
        val sanitized = raw.map { char ->
            when {
                char.isLetterOrDigit() -> char
                char == '-' || char == '_' || char == '.' -> char
                else -> '-'
            }
        }.joinToString("")
        return sanitized.take(64)
    }

    private fun compareVersions(left: String, right: String): Int {
        val leftParts = left.extractVersionParts()
        val rightParts = right.extractVersionParts()
        val max = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until max) {
            val leftValue = leftParts.getOrElse(index) { 0 }
            val rightValue = rightParts.getOrElse(index) { 0 }
            if (leftValue != rightValue) {
                return leftValue.compareTo(rightValue)
            }
        }
        return left.compareTo(right)
    }

    private fun String.extractVersionParts(): List<Int> =
        trim()
            .removePrefix("v")
            .takeWhile { it.isDigit() || it == '.' }
            .split('.')
            .filter { it.isNotBlank() }
            .mapNotNull { it.toIntOrNull() }
}

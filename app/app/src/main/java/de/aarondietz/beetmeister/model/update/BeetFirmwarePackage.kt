package de.aarondietz.beetmeister.model.update

data class BeetFirmwareMetadata(
    val productId: String,
    val hardwareRev: String,
    val firmwareVersion: String,
    val buildLabel: String,
    val maintenanceProtocolVersion: Int,
    val runtimeProtocolVersion: Int,
    val imageKind: String,
    val compatibleHardwareRevs: List<String>,
)

enum class BeetFirmwareSource {
    Bundled,
    Custom,
}

data class BeetFirmwarePackageSummary(
    val source: BeetFirmwareSource,
    val sourceLabel: String,
    val assetId: String,
    val metadata: BeetFirmwareMetadata,
    val imageSize: Int,
    val sha256Hex: String,
    val isDowngrade: Boolean,
    val runtimeProtocolWarning: Boolean,
)

enum class BeetMaintenanceUpdatePhase {
    Idle,
    Ready,
    Uploading,
    Reconnecting,
    Rebooting,
    Completed,
    Failed,
}

data class BeetMaintenanceUpdateState(
    val bundledFirmware: BeetFirmwarePackageSummary? = null,
    val selectedFirmware: BeetFirmwarePackageSummary? = null,
    val phase: BeetMaintenanceUpdatePhase = BeetMaintenanceUpdatePhase.Idle,
    val bytesTransferred: Int = 0,
    val totalBytes: Int = 0,
    val elapsedSeconds: Int = 0,
    val estimatedRemainingSeconds: Int? = null,
    val retryCount: Int = 0,
    val statusDetail: String? = null,
    val errorDetail: String? = null,
)

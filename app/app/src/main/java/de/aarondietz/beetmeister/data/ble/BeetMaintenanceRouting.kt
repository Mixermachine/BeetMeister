package de.aarondietz.beetmeister.data.ble

import de.aarondietz.beetmeister.model.controller.BeetMaintenanceInfo

internal enum class BeetMaintenanceRoute {
    Runtime,
    ForcedUpdate,
}

internal fun determineMaintenanceRoute(
    info: BeetMaintenanceInfo,
    runtimeServiceAvailable: Boolean,
    supportedRuntimeProtocolVersion: Int,
): BeetMaintenanceRoute {
    if (!runtimeServiceAvailable) {
        return BeetMaintenanceRoute.ForcedUpdate
    }
    if (!info.updateCapable) {
        return BeetMaintenanceRoute.Runtime
    }
    return if (info.runtimeProtocolVersion == supportedRuntimeProtocolVersion) {
        BeetMaintenanceRoute.Runtime
    } else {
        BeetMaintenanceRoute.ForcedUpdate
    }
}

package de.aarondietz.beetmeister.data.ble

import de.aarondietz.beetmeister.BuildConfig
import de.aarondietz.beetmeister.model.controller.BeetMaintenanceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class BeetMaintenanceRoutingTest {
    private val baseInfo = BeetMaintenanceInfo(
        productId = "beetmeister",
        hardwareRev = "rev_a",
        firmwareVersion = "0.2.0",
        buildLabel = "v0.2.0",
        maintenanceProtocolVersion = 1,
        runtimeProtocolVersion = BuildConfig.BEET_RUNTIME_PROTOCOL_VERSION,
        updateCapable = true,
        imageKind = "bundled",
    )

    @Test
    fun routesToRuntimeWhenRuntimeServiceAndProtocolMatch() {
        assertEquals(
            BeetMaintenanceRoute.Runtime,
            determineMaintenanceRoute(
                baseInfo,
                runtimeServiceAvailable = true,
                supportedRuntimeProtocolVersion = BuildConfig.BEET_RUNTIME_PROTOCOL_VERSION,
            ),
        )
    }

    @Test
    fun routesToForcedUpdateWhenProtocolDiffers() {
        assertEquals(
            BeetMaintenanceRoute.ForcedUpdate,
            determineMaintenanceRoute(
                baseInfo.copy(runtimeProtocolVersion = 8),
                runtimeServiceAvailable = true,
                supportedRuntimeProtocolVersion = BuildConfig.BEET_RUNTIME_PROTOCOL_VERSION,
            ),
        )
    }

    @Test
    fun routesToForcedUpdateWhenRuntimeServiceMissing() {
        assertEquals(
            BeetMaintenanceRoute.ForcedUpdate,
            determineMaintenanceRoute(
                baseInfo,
                runtimeServiceAvailable = false,
                supportedRuntimeProtocolVersion = BuildConfig.BEET_RUNTIME_PROTOCOL_VERSION,
            ),
        )
    }
}

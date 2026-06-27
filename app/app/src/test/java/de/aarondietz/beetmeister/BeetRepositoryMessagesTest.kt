package de.aarondietz.beetmeister

import de.aarondietz.beetmeister.data.repository.commandMessageForResult
import de.aarondietz.beetmeister.model.command.BeetCommandResult
import org.junit.Assert.assertEquals
import org.junit.Test

class BeetRepositoryMessagesTest {
    private val strings = TestBeetStringResolver()

    @Test
    fun clearBleBondsMessageUsesExplicitReasons() {
        val cleared = BeetCommandResult(
            command = "clear_ble_bonds",
            pairIndex = null,
            status = "accepted",
            reason = "bonds_cleared",
        )
        val none = BeetCommandResult(
            command = "clear_ble_bonds",
            pairIndex = null,
            status = "accepted",
            reason = "no_bonds",
        )

        assertEquals("Bluetooth bonds cleared.", commandMessageForResult(cleared, strings))
        assertEquals("No Bluetooth bonds to clear.", commandMessageForResult(none, strings))
    }

    @Test
    fun commandMessageCoversBusyAndRateLimited() {
        val busy = BeetCommandResult(
            command = "manual_start",
            pairIndex = 2,
            status = "rejected",
            reason = "busy",
        )
        val rateLimited = BeetCommandResult(
            command = "manual_start",
            pairIndex = 2,
            status = "rejected",
            reason = "rate_limited",
        )

        assertEquals("Pair 2: Controller is busy.", commandMessageForResult(busy, strings))
        assertEquals("Pair 2: Too many commands; try again.", commandMessageForResult(rateLimited, strings))
    }

    @Test
    fun rebootAndFactoryResetMessagesUseExplicitText() {
        val rebooting = BeetCommandResult(
            command = "reboot_controller",
            pairIndex = null,
            status = "accepted",
            reason = "rebooting",
        )
        val factoryReset = BeetCommandResult(
            command = "factory_reset",
            pairIndex = null,
            status = "accepted",
            reason = "factory_reset_started",
        )

        assertEquals("Controller is rebooting.", commandMessageForResult(rebooting, strings))
        assertEquals(
            "Factory reset started. The controller will reboot and must be paired again.",
            commandMessageForResult(factoryReset, strings),
        )
    }
}

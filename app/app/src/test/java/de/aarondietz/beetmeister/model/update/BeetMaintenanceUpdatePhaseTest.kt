package de.aarondietz.beetmeister.model.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeetMaintenanceUpdatePhaseTest {
    @Test
    fun activeMaintenancePhasesIncludeStartingAndTransferStates() {
        assertTrue(BeetMaintenanceUpdatePhase.Starting.isActiveMaintenancePhase())
        assertTrue(BeetMaintenanceUpdatePhase.Uploading.isActiveMaintenancePhase())
        assertTrue(BeetMaintenanceUpdatePhase.Reconnecting.isActiveMaintenancePhase())
        assertTrue(BeetMaintenanceUpdatePhase.Rebooting.isActiveMaintenancePhase())
    }

    @Test
    fun inactiveMaintenancePhasesExcludeIdleReadyCompletedAndFailed() {
        assertFalse(BeetMaintenanceUpdatePhase.Idle.isActiveMaintenancePhase())
        assertFalse(BeetMaintenanceUpdatePhase.Ready.isActiveMaintenancePhase())
        assertFalse(BeetMaintenanceUpdatePhase.Completed.isActiveMaintenancePhase())
        assertFalse(BeetMaintenanceUpdatePhase.Failed.isActiveMaintenancePhase())
    }
}

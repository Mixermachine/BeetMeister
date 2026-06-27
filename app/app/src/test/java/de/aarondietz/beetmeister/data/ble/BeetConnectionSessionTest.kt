package de.aarondietz.beetmeister.data.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeetConnectionSessionTest {
    @Test
    fun initialSyncCompletesAfterControllerInfoAndDeviceFrame() {
        val session = BeetConnectionSession()

        session.markControllerInfoLoaded(pairCount = 8)
        assertFalse(session.tryCompleteInitialSync())

        session.initialDeviceFrameReceived = true

        assertTrue(session.tryCompleteInitialSync())
        assertFalse(session.tryCompleteInitialSync())
    }

    @Test
    fun initialSyncDoesNotRequireAllPairFrames() {
        val session = BeetConnectionSession()

        session.markPairSynced(4)
        session.markPairSynced(5)
        session.markControllerInfoLoaded(pairCount = 8)

        assertTrue(session.tryCompleteInitialSync())
    }

    @Test
    fun initialSyncDoesNotRequireDeviceFrameWhenPairStateAlreadyArrived() {
        val session = BeetConnectionSession()

        session.markPairSynced(3)
        session.markControllerInfoLoaded(pairCount = 8)

        assertTrue(session.tryCompleteInitialSync())
    }

    @Test
    fun resetClearsInitialSyncGate() {
        val session = BeetConnectionSession()

        session.markControllerInfoLoaded(pairCount = 8)
        session.initialDeviceFrameReceived = true
        assertTrue(session.tryCompleteInitialSync())

        session.resetSyncState()

        assertFalse(session.tryCompleteInitialSync())
    }
}

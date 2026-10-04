package de.aarondietz.beetmeister.model.repository

import de.aarondietz.beetmeister.model.controller.BeetPairCombined
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeetPairCombinedExtensionsTest {

    @Test
    fun defaultStateHasNoLeadsOrFollowers() {
        val state = BeetRepositoryState()

        assertFalse(state.isPairCombinedLoaded)
        for (p in 1..8) {
            assertNull(state.leadFor(p))
            assertTrue(state.followersFor(p).isEmpty())
            assertFalse(state.isFollower(p))
            assertFalse(state.isLead(p))
        }
    }

    @Test
    fun leadForDetectsFollowersCorrectly() {
        // Pair 1 leads Pair 3 (bit 2 = 4) and Pair 4 (bit 3 = 8) -> mask = 12
        val combinedMap = mapOf(
            1 to BeetPairCombined(pairIndex = 1, followersMask = (1 shl 2) or (1 shl 3)),
        )
        val state = BeetRepositoryState(pairCombined = combinedMap)

        assertEquals(1, state.leadFor(3))
        assertEquals(1, state.leadFor(4))
        assertNull(state.leadFor(1))
        assertNull(state.leadFor(2))
        assertNull(state.leadFor(5))

        assertTrue(state.isFollower(3))
        assertTrue(state.isFollower(4))
        assertFalse(state.isFollower(1))

        assertTrue(state.isLead(1))
        assertFalse(state.isLead(3))
        assertEquals(listOf(3, 4), state.followersFor(1))
    }

    @Test
    fun availableLeadsExcludesSelfAndFollowers() {
        // Pair 1 leads Pair 3
        val combinedMap = mapOf(
            1 to BeetPairCombined(pairIndex = 1, followersMask = (1 shl 2)),
        )
        val state = BeetRepositoryState(pairCombined = combinedMap)

        // For Pair 2: can follow 1, 4, 5, 6, 7, 8 (cannot follow 2 self, cannot follow 3 because 3 is follower)
        val availableFor2 = state.availableLeadsFor(2)
        assertEquals(listOf(1, 4, 5, 6, 7, 8), availableFor2)

        // For Pair 1 (which is already a lead): cannot follow anyone until its followers are cleared
        assertTrue(state.availableLeadsFor(1).isEmpty())

        // For Pair 3 (currently following 1): can switch to any non-follower non-self (1, 2, 4, 5, 6, 7, 8)
        val availableFor3 = state.availableLeadsFor(3)
        assertEquals(listOf(1, 2, 4, 5, 6, 7, 8), availableFor3)
    }

    @Test
    fun invalidPairIndicesReturnNullOrEmpty() {
        val state = BeetRepositoryState(
            pairCombined = mapOf(1 to BeetPairCombined(1, followersMask = 0xFF)),
        )

        assertNull(state.leadFor(0))
        assertNull(state.leadFor(9))
        assertTrue(state.followersFor(0).isEmpty())
        assertTrue(state.followersFor(9).isEmpty())
        assertTrue(state.availableLeadsFor(0).isEmpty())
        assertTrue(state.availableLeadsFor(9).isEmpty())
    }
}

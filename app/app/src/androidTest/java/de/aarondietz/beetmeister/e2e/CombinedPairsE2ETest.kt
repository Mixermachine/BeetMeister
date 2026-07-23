package de.aarondietz.beetmeister.e2e

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import de.aarondietz.beetmeister.MainActivity
import de.aarondietz.beetmeister.data.repository.BeetRepository
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

/**
 * E2E: combined-pairs suite — store / readback / clear via BLE.
 *
 * Tests the lead/follower sensor-sharing configuration.
 * No UI interaction needed — the BLE commands `store_pair_combined`
 * and `get_pair_combined` are exercised through the app's
 * [BeetRepository] singleton (accessible via Koin).
 *
 * Config management UI is deferred per the combined-pairs plan,
 * so this test uses the repository directly. The test runs in
 * the same process as the app, so Koin singletons are available.
 *
 * B values:
 *  - pair 1 leads pair 3 → followersMask=4 (bit 2)
 *  - pair 2 leads pair 5 → followersMask=16 (bit 4)
 *  - clear → followersMask=0
 *
 * Isolation: each @Test stores its own config, reads back, and
 * clears (cleanup). If the test fails mid-way, the next test's
 * store overrides. No permanent config is left on the controller.
 */
@E2e
class CombinedPairsE2ETest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var fixture: E2eConnectionFixture
    private lateinit var repository: BeetRepository

    @Before
    fun setUp() {
        fixture = E2eConnectionFixture(composeRule, testSlug = "combinedPairs")
        fixture.connectOnce()

        val koin = GlobalContext.get()
        repository = koin.get()
    }

    @Test(timeout = 60_000)
    fun pair1LeadsPair3_readbackAndClear() {
        storeAndReadback(pairIndex = 1, followersMask = 4)
    }

    @Test(timeout = 60_000)
    fun pair2LeadsPair5_readbackAndClear() {
        storeAndReadback(pairIndex = 2, followersMask = 16)
    }

    @Test(timeout = 60_000)
    fun clearCombinedConfigOnPair1() {
        // First set a config, then clear and verify
        repository.storePairCombined(1, 4)
        composeRule.waitUntil(timeoutMillis = 15_000) {
            repository.state.value.pairCombined.containsKey(1)
        }
        val afterStore = repository.state.value.pairCombined[1]
        check(afterStore != null && afterStore.followersMask == 4) {
            "Expected pairCombined[1].followersMask=4 after store, got $afterStore"
        }

        // Clear
        repository.storePairCombined(1, 0)
        composeRule.waitUntil(timeoutMillis = 15_000) {
            val entry = repository.state.value.pairCombined[1]
            entry == null || entry.followersMask == 0
        }
        val afterClear = repository.state.value.pairCombined[1]
        check(afterClear == null || afterClear.followersMask == 0) {
            "Expected pairCombined[1] absent or followersMask=0 after clear, got $afterClear"
        }
    }

    private fun storeAndReadback(pairIndex: Int, followersMask: Int) {
        // Store
        repository.storePairCombined(pairIndex, followersMask)

        // Wait for BLE response (async)
        composeRule.waitUntil(timeoutMillis = 15_000) {
            val entry = repository.state.value.pairCombined[pairIndex]
            entry != null && entry.followersMask == followersMask
        }

        // Readback via BLE get_pair_combined
        repository.loadPairCombined(pairIndex)
        composeRule.waitUntil(timeoutMillis = 15_000) {
            val entry = repository.state.value.pairCombined[pairIndex]
            entry != null && entry.followersMask == followersMask
        }

        val result = repository.state.value.pairCombined[pairIndex]
        check(result != null) { "pairCombined[$pairIndex] is null after load" }
        check(result.followersMask == followersMask) {
            "Expected followersMask=$followersMask, got ${result.followersMask}"
        }

        // Cleanup: clear the config
        repository.storePairCombined(pairIndex, 0)
        composeRule.waitUntil(timeoutMillis = 15_000) {
            val entry = repository.state.value.pairCombined[pairIndex]
            entry == null || entry.followersMask == 0
        }
    }
}

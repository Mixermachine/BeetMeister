package de.aarondietz.beetmeister.model.repository

import de.aarondietz.beetmeister.model.controller.BeetPairCombined

/**
 * Helper extension functions on [BeetRepositoryState] for Combined Pairs (Sensor Piggybacking).
 *
 * In firmware:
 * - A lead pair stores a `followers_mask` (bitmask where bit `(p - 1)` indicates pair `p` is a follower).
 * - A follower pair piggybacks on the lead's physical moisture sensor readings.
 * - Firmware enforces: no self-reference, and a follower cannot be claimed by multiple leads.
 */

/**
 * Returns the pair index (1-based) of the lead pair that [pairIndex] follows, or null if [pairIndex] uses its own sensor.
 */
fun BeetRepositoryState.leadFor(pairIndex: Int): Int? {
    if (pairIndex < 1 || pairIndex > displayedPairCount) return null
    val targetBit = 1 shl (pairIndex - 1)
    return pairCombined.entries.firstOrNull { (leadIdx, combined) ->
        leadIdx != pairIndex && (combined.followersMask and targetBit) != 0
    }?.key
}

/**
 * Returns the sorted list of pair indices (1-based) that follow [pairIndex].
 * Returns empty list if no pairs follow [pairIndex].
 */
fun BeetRepositoryState.followersFor(pairIndex: Int): List<Int> {
    if (pairIndex < 1 || pairIndex > displayedPairCount) return emptyList()
    val mask = pairCombined[pairIndex]?.followersMask ?: return emptyList()
    return (1..displayedPairCount).filter { p ->
        p != pairIndex && (mask and (1 shl (p - 1))) != 0
    }
}

/**
 * True if [pairIndex] follows another pair's sensor.
 */
fun BeetRepositoryState.isFollower(pairIndex: Int): Boolean = leadFor(pairIndex) != null

/**
 * True if one or more pairs follow [pairIndex]'s sensor.
 */
fun BeetRepositoryState.isLead(pairIndex: Int): Boolean = followersFor(pairIndex).isNotEmpty()

/**
 * Computes the list of candidate lead pairs that [pairIndex] is allowed to follow:
 * - Cannot follow itself.
 * - Cannot follow a pair that is currently a follower (no chaining / transitive piggybacking).
 * - If [pairIndex] is currently a lead (has followers), it cannot become a follower without clearing its followers first.
 */
fun BeetRepositoryState.availableLeadsFor(pairIndex: Int): List<Int> {
    if (pairIndex < 1 || pairIndex > displayedPairCount) return emptyList()
    if (isLead(pairIndex)) return emptyList()

    return (1..displayedPairCount).filter { candidate ->
        candidate != pairIndex && !isFollower(candidate)
    }
}

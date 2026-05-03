package de.aarondietz.beetmeister.beet.model.event

import com.squareup.moshi.Json

data class BeetHistorySummary(
    @param:Json(name = "latest_seq_no") val latestSequenceNumber: Long,
    @param:Json(name = "event_count") val eventCount: Int,
    @param:Json(name = "pair_totals_s") val pairTotalsSeconds: List<Int>,
)

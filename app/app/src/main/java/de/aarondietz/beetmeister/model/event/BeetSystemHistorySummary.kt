package de.aarondietz.beetmeister.model.event

import com.squareup.moshi.Json

data class BeetSystemHistorySummary(
    @param:Json(name = "latest_seq_no") val latestSequenceNumber: Long,
    @param:Json(name = "event_count") val eventCount: Int,
)

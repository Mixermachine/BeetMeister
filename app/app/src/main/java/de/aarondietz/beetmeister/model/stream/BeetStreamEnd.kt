package de.aarondietz.beetmeister.model.stream

import com.squareup.moshi.Json

/**
 * Terminal frame of a runtime stream_events burst, sent on the state stream
 * after (or instead of) the promised event frames.
 */
data class BeetStreamEnd(
    @param:Json(name = "id") val streamId: Long,
    @param:Json(name = "kind") val kind: String,
    @param:Json(name = "status") val status: String,
    @param:Json(name = "delivered") val delivered: Long,
    @param:Json(name = "last_seq") val lastSeq: Long,
    @param:Json(name = "gaps") val gaps: Long,
)

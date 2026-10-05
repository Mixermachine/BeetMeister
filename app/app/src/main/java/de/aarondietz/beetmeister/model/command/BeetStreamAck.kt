package de.aarondietz.beetmeister.model.command

import com.squareup.moshi.Json

/**
 * Ack for a runtime stream_events command: the controller accepts and arms a
 * notification pump delivering events for one kind starting at [fromSeq].
 */
data class BeetStreamAck(
    @param:Json(name = "stream_id") val streamId: Long,
    @param:Json(name = "kind") val kind: String,
    @param:Json(name = "from_seq") val fromSeq: Long,
    @param:Json(name = "latest_seq") val latestSeq: Long,
    @param:Json(name = "total") val total: Long,
)

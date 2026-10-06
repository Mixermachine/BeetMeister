package de.aarondietz.beetmeister.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
internal interface BeetEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertWatering(events: List<BeetWateringEventEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertSystem(events: List<BeetSystemEventEntity>)

    @Query("SELECT * FROM watering_events WHERE deviceId = :deviceId ORDER BY seqNo DESC")
    fun loadWatering(deviceId: String): List<BeetWateringEventEntity>

    @Query("SELECT * FROM system_events WHERE deviceId = :deviceId ORDER BY seqNo DESC")
    fun loadSystem(deviceId: String): List<BeetSystemEventEntity>

    /*
     * Retention mirrors the legacy cache semantics: rows with bootId == 0 are
     * corrupt/placeholder and dropped; time-valid rows older than the cutoff
     * are pruned; rows without a valid wall clock time are always retained.
     */
    @Query(
        "DELETE FROM watering_events WHERE deviceId = :deviceId AND " +
            "(bootId = 0 OR (startedAt > 0 AND endedAt > 0 AND endedAt < :cutoffUnixSeconds))",
    )
    fun pruneWatering(deviceId: String, cutoffUnixSeconds: Long)

    @Query(
        "DELETE FROM system_events WHERE deviceId = :deviceId AND " +
            "(bootId = 0 OR (unixSeconds > 0 AND unixSeconds < :cutoffUnixSeconds))",
    )
    fun pruneSystem(deviceId: String, cutoffUnixSeconds: Long)

    @Transaction
    fun saveWateringBatch(events: List<BeetWateringEventEntity>, cutoffUnixSeconds: Long) {
        insertWatering(events)
        events.firstOrNull()?.let { pruneWatering(it.deviceId, cutoffUnixSeconds) }
    }

    @Transaction
    fun saveSystemBatch(events: List<BeetSystemEventEntity>, cutoffUnixSeconds: Long) {
        insertSystem(events)
        events.firstOrNull()?.let { pruneSystem(it.deviceId, cutoffUnixSeconds) }
    }

    @Query("SELECT watermarkSeq FROM sync_state WHERE deviceId = :deviceId AND kind = :kind")
    fun watermark(deviceId: String, kind: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putWatermark(row: BeetSyncStateEntity)

    @Query("DELETE FROM watering_events WHERE deviceId = :deviceId")
    fun clearWatering(deviceId: String)

    @Query("DELETE FROM system_events WHERE deviceId = :deviceId")
    fun clearSystem(deviceId: String)

    @Query("DELETE FROM sync_state WHERE deviceId = :deviceId")
    fun clearSyncState(deviceId: String)

    @Transaction
    fun clearDevice(deviceId: String) {
        clearWatering(deviceId)
        clearSystem(deviceId)
        clearSyncState(deviceId)
    }

    @Query("SELECT COUNT(*) FROM watering_events WHERE deviceId = :deviceId")
    fun wateringCount(deviceId: String): Int

    @Query("SELECT COUNT(*) FROM system_events WHERE deviceId = :deviceId")
    fun systemCount(deviceId: String): Int
}

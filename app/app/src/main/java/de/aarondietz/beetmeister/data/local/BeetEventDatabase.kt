package de.aarondietz.beetmeister.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        BeetWateringEventEntity::class,
        BeetSystemEventEntity::class,
        BeetSyncStateEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
internal abstract class BeetEventDatabase : RoomDatabase() {
    abstract fun eventDao(): BeetEventDao

    companion object {
        const val DATABASE_NAME = "beet_events.db"

        fun create(context: Context): BeetEventDatabase =
            Room.databaseBuilder(context.applicationContext, BeetEventDatabase::class.java, DATABASE_NAME)
                .build()

        fun createInMemory(context: Context): BeetEventDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, BeetEventDatabase::class.java)
                .build()
    }
}

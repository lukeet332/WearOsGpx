package com.wearosgpx.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [RunEntity::class, TrackPointEntity::class, LapEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class WearGpxDatabase : RoomDatabase() {

    abstract fun runDao(): RunDao

    companion object {
        @Volatile
        private var INSTANCE: WearGpxDatabase? = null

        fun getInstance(context: Context): WearGpxDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    WearGpxDatabase::class.java,
                    "wear_gpx.db",
                )
                    // Track points are appended very frequently; foreign keys keep
                    // them tied to a valid run and cascade on delete.
                    // Dev-only: wipe on schema change rather than writing migrations.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}

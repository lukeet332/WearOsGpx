package com.wearosgpx.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RunDao {

    // --- Writes (called from the tracking service on a background dispatcher) ---

    @Insert
    suspend fun insertRun(run: RunEntity): Long

    @Update
    suspend fun updateRun(run: RunEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrackPoint(point: TrackPointEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrackPoints(points: List<TrackPointEntity>)

    @Query("DELETE FROM runs WHERE runId = :runId")
    suspend fun deleteRun(runId: Long)

    @Query("UPDATE runs SET isSynced = 1 WHERE runId = :runId")
    suspend fun markSynced(runId: Long)

    // --- Reads ---

    @Query("SELECT * FROM runs ORDER BY startEpochMillis DESC")
    fun observeRuns(): Flow<List<RunEntity>>

    @Query("SELECT * FROM runs WHERE runId = :runId")
    suspend fun getRun(runId: Long): RunEntity?

    /** Live stream of one run + its points, sorted chronologically. */
    @Transaction
    @Query(
        "SELECT * FROM runs WHERE runId = :runId"
    )
    fun observeRunWithPoints(runId: Long): Flow<RunWithTrackPoints?>

    @Transaction
    @Query("SELECT * FROM runs WHERE runId = :runId")
    suspend fun getRunWithPoints(runId: Long): RunWithTrackPoints?

    /** Just the points, oldest first — for drawing the breadcrumb/elevation canvases. */
    @Query("SELECT * FROM track_points WHERE runId = :runId ORDER BY epochMillis ASC")
    fun observeTrackPoints(runId: Long): Flow<List<TrackPointEntity>>

    @Query("SELECT * FROM runs WHERE isComplete = 1 AND isSynced = 0 ORDER BY startEpochMillis ASC")
    suspend fun getUnsyncedCompletedRuns(): List<RunEntity>

    /** Most recently finished run — backs the post-run summary screen. */
    @Query("SELECT * FROM runs WHERE isComplete = 1 ORDER BY endEpochMillis DESC LIMIT 1")
    fun observeMostRecentCompletedRun(): Flow<RunEntity?>

    // --- Laps / splits ---

    @Insert
    suspend fun insertLap(lap: LapEntity): Long

    @Query("SELECT * FROM laps WHERE runId = :runId ORDER BY lapNumber ASC")
    fun observeLaps(runId: Long): Flow<List<LapEntity>>
}

package com.wearosgpx.data.repository

import com.wearosgpx.data.local.LapEntity
import com.wearosgpx.data.local.RunDao
import com.wearosgpx.data.local.RunEntity
import com.wearosgpx.data.local.RunWithTrackPoints
import com.wearosgpx.data.local.TrackPointEntity
import kotlinx.coroutines.flow.Flow

/**
 * Single entry point for run data. The tracking engine (Phase 2) writes through
 * here; the UI (Phase 3) and the sync engine (Phase 4) read through here. Keeps
 * Room out of the rest of the app.
 */
class RunRepository(private val dao: RunDao) {

    fun observeRuns(): Flow<List<RunEntity>> = dao.observeRuns()

    fun observeRunWithPoints(runId: Long): Flow<RunWithTrackPoints?> =
        dao.observeRunWithPoints(runId)

    fun observeTrackPoints(runId: Long): Flow<List<TrackPointEntity>> =
        dao.observeTrackPoints(runId)

    suspend fun getRunWithPoints(runId: Long): RunWithTrackPoints? =
        dao.getRunWithPoints(runId)

    suspend fun getUnsyncedCompletedRuns(): List<RunEntity> =
        dao.getUnsyncedCompletedRuns()

    fun observeMostRecentCompletedRun(): Flow<RunEntity?> =
        dao.observeMostRecentCompletedRun()

    suspend fun addLap(lap: LapEntity): Long = dao.insertLap(lap)

    fun observeLaps(runId: Long): Flow<List<LapEntity>> = dao.observeLaps(runId)

    /** Opens a new in-progress run and returns its generated id. */
    suspend fun startRun(startEpochMillis: Long, title: String = "Run"): Long =
        dao.insertRun(
            RunEntity(startEpochMillis = startEpochMillis, title = title)
        )

    /**
     * Batch-appends a WHS delivery's worth of points in a single transaction.
     * The caller supplies pre-computed cumulative distances (tracked in memory),
     * so we never re-read the whole track per sample — important for keeping the
     * app processor asleep between the co-processor's batched deliveries.
     */
    suspend fun addTrackPoints(points: List<TrackPointEntity>) {
        if (points.isNotEmpty()) dao.insertTrackPoints(points)
    }

    /** Recomputes summary fields and marks the run complete. */
    suspend fun finishRun(runId: Long, endEpochMillis: Long) {
        val run = dao.getRunWithPoints(runId) ?: return
        val points = run.points
        val hrValues = points.mapNotNull { it.heartRateBpm }
        val updated = run.run.copy(
            endEpochMillis = endEpochMillis,
            totalDistanceMeters = points.lastOrNull()?.cumulativeDistanceMeters ?: 0.0,
            totalDurationMillis = endEpochMillis - run.run.startEpochMillis,
            totalAscentMeters = ascent(points),
            avgHeartRateBpm = hrValues.takeIf { it.isNotEmpty() }?.average(),
            maxHeartRateBpm = hrValues.maxOrNull(),
            avgSpeedMetersPerSec = averageSpeed(points, endEpochMillis, run.run.startEpochMillis),
            isComplete = true,
        )
        dao.updateRun(updated)
    }

    suspend fun deleteRun(runId: Long) = dao.deleteRun(runId)
    suspend fun markSynced(runId: Long) = dao.markSynced(runId)

    private fun ascent(points: List<TrackPointEntity>): Double {
        var gain = 0.0
        var prev: Double? = null
        for (p in points) {
            val ele = p.altitudeMeters ?: continue
            prev?.let { if (ele > it) gain += ele - it }
            prev = ele
        }
        return gain
    }

    private fun averageSpeed(
        points: List<TrackPointEntity>,
        endMs: Long,
        startMs: Long,
    ): Double? {
        val distance = points.lastOrNull()?.cumulativeDistanceMeters ?: return null
        val seconds = (endMs - startMs) / 1000.0
        return if (seconds > 0) distance / seconds else null
    }
}

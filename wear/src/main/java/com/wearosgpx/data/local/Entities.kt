package com.wearosgpx.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * One recorded workout session. Summary fields are filled progressively by the
 * tracking engine (Phase 2) and finalized when the run completes.
 */
@Entity(tableName = "runs")
data class RunEntity(
    @PrimaryKey(autoGenerate = true) val runId: Long = 0,
    val title: String = "Run",
    val startEpochMillis: Long,
    val endEpochMillis: Long? = null,

    // Running totals / summary
    val totalDistanceMeters: Double = 0.0,
    val totalDurationMillis: Long = 0,
    val totalAscentMeters: Double = 0.0,
    val avgHeartRateBpm: Double? = null,
    val maxHeartRateBpm: Double? = null,
    val avgSpeedMetersPerSec: Double? = null,

    /** False while recording; flipped true when the session is finalized. */
    val isComplete: Boolean = false,

    /** Phase 4: set once the run has been handed off to the phone. */
    val isSynced: Boolean = false,
)

/**
 * A single GPS sample belonging to a run. Rows are appended in real time by the
 * tracking service. Deleting a run cascades to its points.
 */
@Entity(
    tableName = "track_points",
    foreignKeys = [
        ForeignKey(
            entity = RunEntity::class,
            parentColumns = ["runId"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("runId")],
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val pointId: Long = 0,
    val runId: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val epochMillis: Long,
    val heartRateBpm: Double? = null,
    val speedMetersPerSec: Double? = null,
    /** Cumulative distance from the run start, in meters. */
    val cumulativeDistanceMeters: Double = 0.0,
)

/**
 * One lap/split of a run — auto-created each kilometre or on a manual lap press.
 */
@Entity(
    tableName = "laps",
    foreignKeys = [
        ForeignKey(
            entity = RunEntity::class,
            parentColumns = ["runId"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("runId")],
)
data class LapEntity(
    @PrimaryKey(autoGenerate = true) val lapId: Long = 0,
    val runId: Long,
    val lapNumber: Int,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val distanceMeters: Double,
    val durationMillis: Long,
    val avgHeartRateBpm: Double? = null,
    val isManual: Boolean = false,
)

/**
 * Room relation: a run together with all of its points, ordered by the query.
 * Used by the UI (elevation/breadcrumb canvases) and by the Phase 4 exporter.
 */
data class RunWithTrackPoints(
    @Embedded val run: RunEntity,
    @Relation(parentColumn = "runId", entityColumn = "runId")
    val points: List<TrackPointEntity>,
)

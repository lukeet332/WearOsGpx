package com.wearosgpx.sync

import com.wearosgpx.data.local.RunWithTrackPoints

/** Maps a stored run + its points into the wire payload sent to the phone. */
fun RunWithTrackPoints.toPayload(): RunPayload = RunPayload(
    startEpochMillis = run.startEpochMillis,
    endEpochMillis = run.endEpochMillis ?: run.startEpochMillis,
    totalDistanceMeters = run.totalDistanceMeters,
    totalDurationMillis = run.totalDurationMillis,
    totalAscentMeters = run.totalAscentMeters,
    avgHeartRateBpm = run.avgHeartRateBpm,
    maxHeartRateBpm = run.maxHeartRateBpm,
    title = run.title,
    points = points
        .sortedBy { it.epochMillis }
        .map {
            PayloadPoint(
                lat = it.latitude,
                lon = it.longitude,
                ele = it.altitudeMeters,
                epochMillis = it.epochMillis,
                hr = it.heartRateBpm,
            )
        },
)

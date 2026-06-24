package com.wearosgpx.health

import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.LocationAvailability

/**
 * Events emitted by [ExerciseClientManager.exerciseUpdateFlow]. Wraps the raw
 * Health Services callback into something a coroutine `Flow` can carry.
 */
sealed class ExerciseMessage {
    data class ExerciseUpdateMessage(val update: ExerciseUpdate) : ExerciseMessage()
    data class LocationAvailabilityMessage(val availability: LocationAvailability) : ExerciseMessage()
}

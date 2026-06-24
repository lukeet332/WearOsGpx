package com.wearosgpx.health

import android.util.Log
import androidx.concurrent.futures.await
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServicesClient
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseCapabilities
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseTrackedStatus
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.LocationAvailability
import androidx.health.services.client.data.WarmUpConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.callbackFlow

/**
 * Thin wrapper around the Health Services [ExerciseClient]. This is where the
 * dual-engine magic happens: by handing an [ExerciseConfig] to WHS, GPS + sensor
 * sampling is offloaded to the watch's low-power co-processor instead of running
 * on the main CPU.
 *
 * Nothing here touches Android lifecycle — that's [com.wearosgpx.service.ExerciseService]'s
 * job. This class is pure WHS plumbing so it stays easy to reason about.
 */
class ExerciseClientManager(healthServicesClient: HealthServicesClient) {

    private val exerciseClient: ExerciseClient = healthServicesClient.exerciseClient

    /** The metrics we want WHS to collect, subject to device capability filtering. */
    private val desiredDataTypes = setOf(
        DataType.HEART_RATE_BPM,
        DataType.LOCATION,
        DataType.DISTANCE_TOTAL,
        DataType.SPEED,
        DataType.CALORIES_TOTAL,
    )

    suspend fun getCapabilities(): ExerciseCapabilities =
        exerciseClient.getCapabilitiesAsync().await()

    /**
     * Warms up GPS + HR before the run starts (Garmin-style "acquiring GPS").
     * Availability updates arrive via [exerciseUpdateFlow]'s onAvailabilityChanged,
     * so by the time the user taps Start there's already a fix.
     */
    suspend fun prepareExercise() {
        val running = getCapabilities().getExerciseTypeCapabilities(ExerciseType.RUNNING)
        // Keep DeltaDataType typing (WarmUpConfig requires Set<DeltaDataType>).
        val warmUpTypes = setOf(DataType.HEART_RATE_BPM, DataType.LOCATION)
            .filter { it in running.supportedDataTypes }
            .toSet()
        val warmUp = WarmUpConfig(ExerciseType.RUNNING, warmUpTypes)
        exerciseClient.prepareExerciseAsync(warmUp).await()
    }

    /**
     * Builds an [ExerciseConfig] for RUNNING, intersecting our desired metrics
     * with what this hardware actually supports, then starts the exercise.
     */
    suspend fun startExercise() {
        val capabilities = getCapabilities()
        val running = capabilities.getExerciseTypeCapabilities(ExerciseType.RUNNING)

        val dataTypes = desiredDataTypes.intersect(running.supportedDataTypes)
        Log.i(TAG, "Starting RUNNING exercise with data types: $dataTypes")

        val config = ExerciseConfig.builder(ExerciseType.RUNNING)
            .setDataTypes(dataTypes)
            .setIsAutoPauseAndResumeEnabled(false)
            .setIsGpsEnabled(true)
            .build()

        exerciseClient.startExerciseAsync(config).await()
    }

    /**
     * Cold flow of exercise messages. Registering the update callback also
     * replays the current exercise (so we recover state after a process restart).
     * The flow unregisters the callback when collection stops.
     */
    val exerciseUpdateFlow = callbackFlow {
        val callback = object : ExerciseUpdateCallback {
            override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
                trySendBlocking(ExerciseMessage.ExerciseUpdateMessage(update))
            }

            override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {
                // Not used yet — laps come in a later iteration.
            }

            override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
                if (availability is LocationAvailability) {
                    trySendBlocking(ExerciseMessage.LocationAvailabilityMessage(availability))
                }
            }

            override fun onRegistered() {
                Log.d(TAG, "Exercise update callback registered.")
            }

            override fun onRegistrationFailed(throwable: Throwable) {
                Log.e(TAG, "Exercise update callback registration failed.", throwable)
            }
        }

        exerciseClient.setUpdateCallback(callback)
        awaitClose { exerciseClient.clearUpdateCallbackAsync(callback) }
    }

    /** True if an exercise owned by this app is already in progress. */
    suspend fun isExerciseInProgress(): Boolean {
        val info = exerciseClient.getCurrentExerciseInfoAsync().await()
        return info.exerciseTrackedStatus == ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS
    }

    suspend fun pauseExercise() = exerciseClient.pauseExerciseAsync().await()
    suspend fun resumeExercise() = exerciseClient.resumeExerciseAsync().await()
    suspend fun endExercise() = exerciseClient.endExerciseAsync().await()

    companion object {
        private const val TAG = "ExerciseClientManager"
    }
}

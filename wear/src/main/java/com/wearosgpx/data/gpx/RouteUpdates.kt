package com.wearosgpx.data.gpx

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process signal that the route catalog changed (import/delete). The route
 * list UI observes [version] and reloads when it ticks — so a route pushed from
 * the phone appears without reopening the screen. Same process as the listener
 * service, so a plain singleton suffices.
 */
object RouteUpdates {
    private val _version = MutableStateFlow(0)
    val version = _version.asStateFlow()

    fun bump() {
        _version.value = _version.value + 1
    }
}

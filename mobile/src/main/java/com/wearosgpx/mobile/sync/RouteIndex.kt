package com.wearosgpx.mobile.sync

import kotlinx.serialization.Serializable

/** Mirror of `com.wearosgpx.sync.RouteIndex` on the watch — keep in sync. */
@Serializable
data class RouteIndex(val routes: List<RouteIndexEntry> = emptyList())

@Serializable
data class RouteIndexEntry(
    val fileName: String?,
    val name: String,
    val distanceMeters: Double,
    val ascentMeters: Double,
    val pointCount: Int,
) {
    val deletable: Boolean get() = fileName != null
}

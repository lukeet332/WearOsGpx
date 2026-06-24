package com.wearosgpx.sync

import kotlinx.serialization.Serializable

/**
 * The watch's catalog of routes, published to the phone so it can list/inspect/
 * delete them. Duplicated in `:mobile` (`com.wearosgpx.mobile.sync`) — keep in sync.
 */
@Serializable
data class RouteIndex(val routes: List<RouteIndexEntry> = emptyList())

@Serializable
data class RouteIndexEntry(
    /** Imported-file name; null for bundled samples (which can't be deleted). */
    val fileName: String?,
    val name: String,
    val distanceMeters: Double,
    val ascentMeters: Double,
    val pointCount: Int,
) {
    val deletable: Boolean get() = fileName != null
}

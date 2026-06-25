package com.wearosgpx.mobile.route

import kotlinx.serialization.Serializable

/**
 * A lightweight, baked vector basemap for the area around a route — built on the
 * phone (from OSM via Overpass) and pushed to the watch alongside the GPX so the
 * watch can draw surrounding roads/paths/water *behind* the breadcrumb with no live
 * map engine and no networking.
 *
 * Mirrors `com.wearosgpx.map.BaseMap` in `:wear` — keep the two in sync.
 *
 * [MapFeature.t] = feature type: 0 major road, 1 minor road, 2 path, 3 water.
 * [MapFeature.c] = flattened coordinates [lat, lon, lat, lon, …] (5-dp rounded).
 */
@Serializable
data class BaseMap(val features: List<MapFeature> = emptyList())

@Serializable
data class MapFeature(val t: Int, val c: List<Double>)

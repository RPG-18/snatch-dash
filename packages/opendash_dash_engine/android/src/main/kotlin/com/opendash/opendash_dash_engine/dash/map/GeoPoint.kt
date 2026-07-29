package com.opendash.opendash_dash_engine.dash.map

/**
 * Minimal lat/lng pair for the native renderer's route polyline.
 *
 * The full nav-math `GeoPoint` (distance/bearing/segment-projection helpers)
 * lives in Dart now (Phase 2) — the route geometry it computes is handed to
 * the native side as plain coordinate pairs for drawing only, which is all
 * [MapRenderer]/[TileProvider] ever needed from it.
 */
data class GeoPoint(val lat: Double, val lng: Double)

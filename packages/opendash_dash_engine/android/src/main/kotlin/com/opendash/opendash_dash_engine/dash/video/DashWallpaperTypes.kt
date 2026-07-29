package com.opendash.opendash_dash_engine.dash.video

/**
 * Local mirror of the original app's `data.DashWallpaperKind`/`DashWallpaperFit`
 * (Phase 5 will own the full wallpaper store in Dart via sqflite; the native
 * idle renderer only needs these two small enums to know how to paint a slot).
 */
enum class DashWallpaperKind { IMAGE, GIF, VIDEO }

enum class DashWallpaperFit { CROP, FIT_HEIGHT, FIT_WIDTH }

package com.opendash.opendash_dash_engine.media

/**
 * Boundary for the wire writer, not the provider. Resolve getters, encode art and commit
 * raw id/title cache BEFORE entering this boundary. Normal enqueue owns card/marker/art
 * ordering. A partial enqueue is retained if it throws; the fallback
 * only appends a card, and must neither clear art nor roll back metadata/cache.
 */
internal inline fun dispatchMediaMetadata(
    metadata: MediaMetadataSnapshot,
    enqueueMetadata: (MediaMetadataSnapshot) -> Unit,
    enqueueCardOnly: (MediaMetadataSnapshot) -> Unit,
) {
    try {
        enqueueMetadata(metadata)
    } catch (_: IllegalStateException) {
        enqueueCardOnly(MediaMetadataSnapshot(title = "", album = "NowPlaying", artist = ""))
    }
}

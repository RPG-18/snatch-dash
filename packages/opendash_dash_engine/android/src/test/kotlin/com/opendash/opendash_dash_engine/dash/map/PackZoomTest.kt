package com.opendash.opendash_dash_engine.dash.map

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The camera floor is read from the packs on disk, not from a constant, and this
 * pins why.
 *
 * `ZOOM_MIN` dropped to 10.00 when the corpus was rebuilt to z10-15 on
 * 2026-09-09, but a phone keeps whatever it downloaded before. On a z11-14 pack
 * the bottom two steps of the new ladder render nothing — MapLibre does not build
 * downwards — and a blank panel mid-ride is indistinguishable from a crash.
 * [corpusMinZoomTakesTheHighestFloor] is the case that matters: mixed packs must
 * take the SHALLOWEST pyramid's floor, because every pack is a live source at once.
 */
class PackZoomTest {

    /**
     * A PMTiles v3 header, filled in only as far as the fields under test. The
     * layout is the one `tools/planetiler/validate_packs.py` reads: 7-byte
     * signature, version, then min_zoom at byte 100 and max_zoom at 101.
     */
    private fun packFile(minZoom: Int, maxZoom: Int = 15, version: Int = 3, size: Int = 127): File {
        val head = ByteArray(size)
        "PMTiles".toByteArray(Charsets.US_ASCII).copyInto(head)
        head[7] = version.toByte()
        if (size > 100) head[100] = minZoom.toByte()
        if (size > 101) head[101] = maxZoom.toByte()
        return File.createTempFile("pack", ".pmtiles").apply { deleteOnExit(); writeBytes(head) }
    }

    @Test
    fun minZoomComesOutOfTheHeader() {
        assertEquals(10, MapStyleFactory.packMinZoom(packFile(minZoom = 10)))
        assertEquals(11, MapStyleFactory.packMinZoom(packFile(minZoom = 11)))
    }

    @Test
    fun corpusMinZoomTakesTheHighestFloor() {
        val newPack = packFile(minZoom = 10)
        val oldPack = packFile(minZoom = 11)
        // The shallowest pyramid wins: below z11 the old pack draws nothing, and it
        // is a live source wherever the rider happens to be.
        assertEquals(11, MapStyleFactory.corpusMinZoom(listOf(newPack, oldPack)))
        assertEquals(11, MapStyleFactory.corpusMinZoom(listOf(oldPack, newPack)))
        assertEquals(10, MapStyleFactory.corpusMinZoom(listOf(newPack)))
    }

    @Test
    fun nothingInstalledHasNoOpinion() {
        assertNull(MapStyleFactory.corpusMinZoom(emptyList()))
    }

    @Test
    fun anUnreadableHeaderHasNoOpinionRatherThanAFloor() {
        // Refusing to zoom out because a byte could not be read would be a worse
        // failure than the blank map this guards, so these all return null and let
        // ZOOM_MIN stand.
        assertNull(MapStyleFactory.packMinZoom(packFile(minZoom = 10, size = 40)), "truncated")
        assertNull(MapStyleFactory.packMinZoom(packFile(minZoom = 10, version = 2)), "wrong version")
        assertNull(
            MapStyleFactory.packMinZoom(File("/nonexistent/nope.pmtiles")),
            "missing file",
        )
        val notPmtiles = File.createTempFile("junk", ".pmtiles")
            .apply { deleteOnExit(); writeBytes(ByteArray(127)) }
        assertNull(MapStyleFactory.packMinZoom(notPmtiles), "bad signature")
    }

    @Test
    fun anUnreadablePackDoesNotHideAReadableOnesFloor() {
        val broken = File("/nonexistent/nope.pmtiles")
        assertEquals(11, MapStyleFactory.corpusMinZoom(listOf(broken, packFile(minZoom = 11))))
    }
}

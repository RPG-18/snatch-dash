package com.opendash.opendash_dash_engine.dash.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VolumeLevelTest {
    @Test
    fun `invalid ranges take precedence over silence`() {
        val ranges = listOf(
            -1 to 10,
            Int.MIN_VALUE to Int.MAX_VALUE,
            Int.MIN_VALUE to Int.MIN_VALUE,
            0 to 0,
            10 to 10,
            10 to 9,
            0 to Int.MIN_VALUE,
            Int.MAX_VALUE to Int.MIN_VALUE,
            Int.MAX_VALUE to Int.MAX_VALUE,
        )
        for ((min, max) in ranges) {
            for (current in listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE)) {
                assertNull(VolumeLevel.encode(current, min, max), "current=$current min=$min max=$max")
            }
        }
    }

    @Test
    fun `nonpositive current is silence even with a positive minimum`() {
        for (current in listOf(Int.MIN_VALUE, -1, 0)) {
            assertEquals(0x10, VolumeLevel.encode(current, 0, 100))
            assertEquals(0x10, VolumeLevel.encode(current, 20, 120))
            assertEquals(0x10, VolumeLevel.encode(current, Int.MAX_VALUE - 1, Int.MAX_VALUE))
        }
    }

    @Test
    fun `positive values at or below minimum use the first bucket`() {
        assertEquals(0x11, VolumeLevel.encode(1, 20, 120))
        assertEquals(0x11, VolumeLevel.encode(19, 20, 120))
        assertEquals(0x11, VolumeLevel.encode(20, 20, 120))
        assertEquals(0x11, VolumeLevel.encode(21, 20, 120))
    }

    @Test
    fun `exact bucket edges and their successors round upward`() {
        val cases = listOf(
            1 to 0x11,
            10 to 0x11, 11 to 0x12,
            20 to 0x12, 21 to 0x13,
            30 to 0x13, 31 to 0x14,
            40 to 0x14, 41 to 0x15,
            50 to 0x15, 51 to 0x16,
            60 to 0x16, 61 to 0x17,
            70 to 0x17, 71 to 0x18,
            80 to 0x18, 81 to 0x19,
            90 to 0x19, 91 to 0x1A,
            100 to 0x1A,
        )
        for ((current, expected) in cases) {
            assertEquals(expected, VolumeLevel.encode(current, 0, 100), "current=$current")
            assertEquals(expected, VolumeLevel.encode(current + 20, 20, 120), "offset current=$current")
        }
    }

    @Test
    fun `uneven ranges use ceiling rather than truncation`() {
        assertEquals(0x14, VolumeLevel.encode(1, 0, 3))
        assertEquals(0x17, VolumeLevel.encode(2, 0, 3))
        assertEquals(0x1A, VolumeLevel.encode(3, 0, 3))
    }

    @Test
    fun `values above maximum clamp to the last bucket`() {
        assertEquals(0x1A, VolumeLevel.encode(121, 20, 120))
        assertEquals(0x1A, VolumeLevel.encode(Int.MAX_VALUE, 20, 120))
    }

    @Test
    fun `maximum integer range does not overflow multiplication or ceiling`() {
        assertEquals(0x11, VolumeLevel.encode(1, 0, Int.MAX_VALUE))
        assertEquals(0x11, VolumeLevel.encode(214748364, 0, Int.MAX_VALUE))
        assertEquals(0x12, VolumeLevel.encode(214748365, 0, Int.MAX_VALUE))
        assertEquals(0x15, VolumeLevel.encode(1073741823, 0, Int.MAX_VALUE))
        assertEquals(0x16, VolumeLevel.encode(1073741824, 0, Int.MAX_VALUE))
        assertEquals(0x1A, VolumeLevel.encode(Int.MAX_VALUE, 0, Int.MAX_VALUE))
        assertEquals(0x11, VolumeLevel.encode(1, Int.MAX_VALUE - 1, Int.MAX_VALUE))
        assertEquals(0x11, VolumeLevel.encode(Int.MAX_VALUE - 1, Int.MAX_VALUE - 1, Int.MAX_VALUE))
        assertEquals(0x1A, VolumeLevel.encode(Int.MAX_VALUE, Int.MAX_VALUE - 1, Int.MAX_VALUE))
    }
}

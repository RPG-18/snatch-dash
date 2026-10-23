package com.opendash.opendash_dash_engine.dash.protocol

internal object VolumeLevel {
    fun encode(current: Int, min: Int, max: Int): Int? {
        if (min < 0 || max <= min) return null
        if (current <= 0) return 0x10

        val range = max.toLong() - min.toLong()
        val offset = current.coerceIn(min, max).toLong() - min.toLong()
        val bucket = ((10L * offset + range - 1L) / range).coerceIn(1L, 10L)
        return 0x10 + bucket.toInt()
    }
}

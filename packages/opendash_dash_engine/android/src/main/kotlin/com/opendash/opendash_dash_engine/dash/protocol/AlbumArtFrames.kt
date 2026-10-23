package com.opendash.opendash_dash_engine.dash.protocol

internal object AlbumArtFrames {
    fun encode(jpeg: ByteArray): List<DashCommand.AlbumArtChunk> {
        val total = jpeg.size
        if (total == 0 || total > 65535) return emptyList()

        val frames = ArrayList<DashCommand.AlbumArtChunk>(maxOf(2, (total + 999) / 1000))
        val firstSize = minOf(total, 1000)
        val first = ByteArray(3 + firstSize)
        first[1] = (total ushr 8).toByte()
        first[2] = total.toByte()
        jpeg.copyInto(first, destinationOffset = 3, endIndex = firstSize)
        frames.add(DashCommand.AlbumArtChunk(first))

        if (total <= 1000) {
            frames.add(DashCommand.AlbumArtChunk(byteArrayOf(0xFF.toByte())))
            return frames
        }

        var offset = firstSize
        var index = 1
        while (offset < total) {
            val size = minOf(1000, total - offset)
            val value = ByteArray(1 + size)
            value[0] = if (offset + size == total) 0xFF.toByte() else index.toByte()
            jpeg.copyInto(value, destinationOffset = 1, startIndex = offset, endIndex = offset + size)
            frames.add(DashCommand.AlbumArtChunk(value))
            offset += size
            index++
        }
        return frames
    }
}

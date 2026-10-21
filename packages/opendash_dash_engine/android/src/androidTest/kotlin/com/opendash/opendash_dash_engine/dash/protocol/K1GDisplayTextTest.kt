package com.opendash.opendash_dash_engine.dash.protocol

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class K1GDisplayTextTest {
    @Test
    fun decomposedMediaNamesFillAllThreeFields() {
        val expected = ascii("e".repeat(20) + "\u0000" + "n".repeat(20) + "\u0000" + "A".repeat(20))
        for ((title, album, artist) in listOf(
            Triple("é".repeat(20), "ñ".repeat(20), "Å".repeat(20)),
            Triple("e\u0301".repeat(20), "n\u0303".repeat(20), "A\u030a".repeat(20)),
        )) {
            assertArrayEquals(expected, value(DashCommand.NowPlaying(title, album, artist), 0x0D))
        }
    }

    @Test
    fun decomposedRouteAndCallerNamesUseTheirFullByteCapacity() {
        for (letter in listOf("é", "e\u0301")) {
            assertArrayEquals(
                ascii("e".repeat(60) + "\u0000"),
                value(DashCommand.RouteCard(letter.repeat(61)), 0x01),
            )
            assertArrayEquals(
                ascii("e".repeat(20) + "\u0000"),
                value(DashCommand.CallNotify(letter.repeat(21)), 0x22),
            )
        }
    }

    @Test
    fun normalizationKeepsCyrillicLettersAndTheLastFittingLatinLetter() {
        for (name in listOf("Йога", "И\u0306ога")) {
            assertArrayEquals(ascii("Yoga\u0000"), value(DashCommand.CallNotify(name), 0x22))
        }
        assertArrayEquals(
            ascii("A".repeat(18) + "eB\u0000"),
            value(DashCommand.CallNotify("A".repeat(18) + "e\u0301B"), 0x22),
        )
    }

    @Test
    fun expansionAndSupplementaryCharactersRespectFieldBoundaries() {
        assertArrayEquals(
            ascii("A".repeat(19) + "s\u0000"),
            value(DashCommand.CallNotify("A".repeat(19) + "ß"), 0x22),
        )
        assertArrayEquals(
            ascii("A".repeat(19) + "?\u0000"),
            value(DashCommand.CallNotify("A".repeat(19) + "😀"), 0x22),
        )
        assertArrayEquals(
            ascii("A??B?\u0000"),
            value(DashCommand.CallNotify("A😀\uD800B\uDC00"), 0x22),
        )
        assertArrayEquals(
            ascii("A\u0000e\t\u0000"),
            value(DashCommand.CallNotify("A\u0000e\u0301\t"), 0x22),
        )
    }

    @Test
    fun truncationPreservesTheRightContextOfCyrillicNames() {
        val prefix = "A".repeat(18) + " "
        val expected = ascii(prefix + "Y")
        val name = prefix + "Ёлкин"
        assertArrayEquals(expected + 0x00.toByte(), value(DashCommand.CallNotify(name), 0x22))
        assertArrayEquals(
            expected + 0x00.toByte() + expected + 0x00.toByte() + expected,
            value(DashCommand.NowPlaying(name, name, name), 0x0D),
        )
        assertArrayEquals(
            ascii("A".repeat(58) + " Y\u0000"),
            value(DashCommand.RouteCard("A".repeat(58) + " Ёлкина"), 0x01),
        )
    }

    @Test
    fun asciiAndEmptyValuesKeepTheirTerminators() {
        assertArrayEquals(ascii("A\u0000B\u0000C"), value(DashCommand.NowPlaying("A", "B", "C"), 0x0D))
        assertArrayEquals(byteArrayOf(0, 0), value(DashCommand.NowPlaying("", "", ""), 0x0D))
        assertArrayEquals(value(DashCommand.CallClear, 0x22), value(DashCommand.CallNotify(""), 0x22))
        assertArrayEquals(byteArrayOf(0), value(DashCommand.RouteCard(""), 0x01))
    }

    private fun ascii(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII)

    private fun value(command: DashCommand, sub: Int): ByteArray {
        val packet = K1GCodec.encode(command)
        assertEquals(packet.size, ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF))
        return K1GPacket.parseIncoming(packet).first { it.type == 0x05 && it.sub == sub }.value
    }
}

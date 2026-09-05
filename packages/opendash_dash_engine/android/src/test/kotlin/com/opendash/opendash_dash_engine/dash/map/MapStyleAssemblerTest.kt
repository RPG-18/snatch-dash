package com.opendash.opendash_dash_engine.dash.map

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * Covers the part of style assembly that is pure string work. Reading assets
 * and preferences needs a device; fanning one template out over N packs does
 * not, and it is where the rules that matter live — layer order, one source per
 * pack, unique ids.
 */
class MapStyleAssemblerTest {

    private val template = """
        {
          "version": 8,
          "glyphs": "asset://glyphs/{fontstack}/{range}.pbf",
          "sprite": "asset://sprites/positron",
          "sources": {"openmaptiles": {"type": "vector", "url": "pmtiles://placeholder"}},
          "layers": [
            {"id": "background", "type": "background"},
            {"id": "water", "type": "fill", "source": "openmaptiles", "source-layer": "water"},
            {"id": "roads", "type": "line", "source": "openmaptiles", "source-layer": "transportation"}
          ]
        }
    """.trimIndent()

    private fun pack(code: String) = File("/data/maps/$code.pmtiles")

    @Test
    fun `one source per pack, addressed through pmtiles`() {
        val style = JSONObject(MapStyleFactory.assemble(template, listOf(pack("ru-ad"), pack("ru-psk"))))
        val sources = style.getJSONObject("sources")

        assertEquals(setOf("pack_ru-ad", "pack_ru-psk"), sources.keys().asSequence().toSet())
        assertEquals(
            "pmtiles://file:///data/maps/ru-ad.pmtiles",
            sources.getJSONObject("pack_ru-ad").getString("url"),
        )
        // The placeholder from the template must not survive — it points nowhere.
        assertFalse(sources.has("openmaptiles"))
    }

    @Test
    fun `a pack URL is fully qualified, three slashes and all`() {
        // The expectation here is copied from MapLibre's contract, NOT from what
        // the code happens to produce: `pmtiles://` wrapping a fully qualified
        // `file://` URL. `File.toURI()` gives `file:/…` with one slash, MapLibre's
        // local file source matches the literal `file://`, and the mismatch does
        // not raise anything — the pack silently never loads. This test exists
        // because its earlier version froze exactly that broken string.
        val url = MapStyleFactory.packUrl(pack("ru-len-spe"))

        assertTrue(url.startsWith("pmtiles://file:///"), "not fully qualified: $url")
        assertEquals("pmtiles://file:///data/maps/ru-len-spe.pmtiles", url)
    }

    @Test
    fun `layers are grouped by layer, not by pack`() {
        val style = JSONObject(MapStyleFactory.assemble(template, listOf(pack("ru-ad"), pack("ru-psk"))))
        val ids = style.getJSONArray("layers").let { array ->
            (0 until array.length()).map { array.getJSONObject(it).getString("id") }
        }

        // Both packs' water before either pack's roads. The other order would
        // draw a neighbouring region's roads over this region's buildings.
        assertEquals(
            listOf("background", "water__ru-ad", "water__ru-psk", "roads__ru-ad", "roads__ru-psk"),
            ids,
        )
    }

    @Test
    fun `every layer id stays unique`() {
        val style = JSONObject(MapStyleFactory.assemble(template, listOf(pack("ru-ad"), pack("ru-psk"))))
        val array = style.getJSONArray("layers")
        val ids = (0 until array.length()).map { array.getJSONObject(it).getString("id") }

        assertEquals(ids.size, ids.toSet().size, "duplicate ids make MapLibre reject the style")
    }

    @Test
    fun `sourceless layers are emitted once, not per pack`() {
        val style = JSONObject(MapStyleFactory.assemble(template, listOf(pack("a"), pack("b"), pack("c"))))
        val array = style.getJSONArray("layers")
        val backgrounds = (0 until array.length())
            .map { array.getJSONObject(it) }
            .count { it.getString("type") == "background" }

        assertEquals(1, backgrounds)
    }

    @Test
    fun `no packs still yields a valid style with just the background`() {
        // This is the "nothing downloaded" frame: a flat fill of the style's
        // background colour. It has to parse, or the dash gets no frame at all
        // rather than an empty one.
        val style = JSONObject(MapStyleFactory.assemble(template, emptyList()))

        assertEquals(0, style.getJSONObject("sources").length())
        assertEquals(1, style.getJSONArray("layers").length())
        assertEquals("background", style.getJSONArray("layers").getJSONObject(0).getString("id"))
    }

    @Test
    fun `glyphs and sprite keep pointing at local assets`() {
        val style = JSONObject(MapStyleFactory.assemble(template, listOf(pack("ru-ad"))))

        // The whole point of the offline switch: no MapTiler, no network.
        assertTrue(style.getString("glyphs").startsWith("asset://"))
        assertTrue(style.getString("sprite").startsWith("asset://"))
    }

    @Test
    fun `theme names map to the prepared style assets`() {
        assertEquals(MapTheme.LIGHT, MapTheme.from(null))
        assertEquals(MapTheme.LIGHT, MapTheme.from("light"))
        assertEquals(MapTheme.DARK, MapTheme.from("dark"))
        // Anything unexpected falls back to the default rather than failing.
        assertEquals(MapTheme.LIGHT, MapTheme.from("solarized"))
        assertEquals("positron", MapTheme.LIGHT.asset)
        assertEquals("dark-matter", MapTheme.DARK.asset)
    }
}

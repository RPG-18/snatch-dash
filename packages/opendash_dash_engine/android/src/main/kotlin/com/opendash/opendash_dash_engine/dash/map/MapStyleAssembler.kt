package com.opendash.opendash_dash_engine.dash.map

import android.content.Context
import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Which of the two prepared styles to draw with. */
enum class MapTheme(val asset: String) {
    LIGHT("positron"),
    DARK("dark-matter");

    companion object {
        fun from(value: String?): MapTheme = if (value == "dark") DARK else LIGHT
    }
}

/**
 * The pure half of style assembly: one template, N packs, one style JSON.
 *
 * Separated from [MapStyleAssembler] because this is the part with rules worth
 * testing — layer order, one source per pack, unique ids — and it needs neither
 * an `AssetManager` nor a `Context` to do its job.
 */
object MapStyleFactory {

    private const val PACK_SUFFIX = ".pmtiles"

    /**
     * Style JSON for [packs], ready for `Style.Builder.fromJson`.
     *
     * The Mapbox style spec ties each layer to exactly one source — there are no
     * source groups — so N packs mean N copies of the whole layer set with the
     * `source` swapped (spec/drawing_from_local_tiles.md, "Источники: несколько
     * паков одновременно").
     *
     * Layers come out **grouped by layer, not by pack**: all packs' water, then
     * all packs' buildings, then all packs' roads. The other order would draw a
     * neighbouring region's roads on top of this region's buildings.
     *
     * With no packs the result still parses: only `background` survives, which
     * is exactly the "nothing downloaded" frame — a flat fill of the style's
     * background colour.
     */
    fun assemble(template: String, packs: List<File>): String {
        val style = JSONObject(template)

        val sources = JSONObject()
        for (pack in packs) {
            sources.put(sourceId(pack), JSONObject().apply {
                put("type", "vector")
                put("url", packUrl(pack))
            })
        }
        style.put("sources", sources)

        val templateLayers = style.getJSONArray("layers")
        val layers = JSONArray()
        for (i in 0 until templateLayers.length()) {
            val layer = templateLayers.getJSONObject(i)
            if (!layer.has("source")) {
                // Background and anything else that draws without data: emitted
                // once, never per pack.
                layers.put(layer)
                continue
            }
            for (pack in packs) {
                val copy = JSONObject(layer.toString())
                copy.put("id", "${layer.getString("id")}__${codeOf(pack)}")
                copy.put("source", sourceId(pack))
                layers.put(copy)
            }
        }
        style.put("layers", layers)
        return style.toString()
    }

    /**
     * How MapLibre wants a pack on local storage addressed: `pmtiles://`, then a
     * **fully qualified** `file://` URL — `file://` plus an absolute path, three
     * slashes in total.
     *
     * Built by hand rather than with `File.toURI()`, and that is the whole point
     * of this function existing: `toURI()` yields `file:/data/…` with a single
     * slash, and MapLibre's local file source accepts a URL only if it starts
     * with the literal `file://`. A pack addressed the `toURI()` way is not
     * rejected loudly — it simply never loads, and the dash shows a flat
     * background with the route hanging over it, which reads as a render bug.
     * Same form as MapLibre's own Android PMTiles example.
     */
    fun packUrl(pack: File): String = "pmtiles://file://${pack.absolutePath}"

    private fun sourceId(pack: File) = "pack_${codeOf(pack)}"

    private fun codeOf(pack: File) = pack.name.removeSuffix(PACK_SUFFIX)
}

/**
 * One assembled style, plus the two inputs the rest of the frame pipeline needs
 * to know about: [theme] decides the colour of the overlays' standby text, and
 * [packs] is the number that multiplies the layer count — the quiet driver of
 * per-frame cost, worth having in the telemetry next to the timings.
 */
data class DashStyle(val json: String, val theme: MapTheme, val packs: Int)

/**
 * Everything style assembly needs from the device: which packs are on disk,
 * which theme the rider picked, and the template out of the module's assets.
 *
 * Both inputs are read **once per stream start** and never again while riding.
 * That is what makes a mid-ride style reload impossible by construction rather
 * than by discipline — see "Сменить набор паков = перезагрузить стиль".
 */
class MapStyleAssembler(private val context: Context) {

    /**
     * Packs the render engine can see.
     *
     * Deliberately the directory and not the app's sqlite registry: the two are
     * separate sources of truth that cannot disagree, because a file only gets
     * its `.pmtiles` name in the same atomic move that writes the registry row.
     * A partially downloaded `.part` file is invisible here by construction.
     */
    fun installedPacks(): List<File> =
        mapsDir().listFiles { f -> f.isFile && f.name.endsWith(PACK_SUFFIX) }
            ?.sortedBy { it.name }
            ?: emptyList()

    /**
     * The rider's choice, read straight out of Flutter's preferences.
     *
     * No method channel for this: the engine reads what it needs when it may.
     *
     * The file name and `flutter.` prefix are `shared_preferences`' private
     * contract, and the same name is also used by its newer DataStore backend.
     * Today the app is on the classic API, so this hits the XML file and they
     * agree. Should it ever move to `SharedPreferencesAsync`, the value would
     * quietly go elsewhere and the dash would sit on the light theme forever —
     * which is why what was actually read gets logged.
     */
    fun theme(): MapTheme {
        val raw = context.getSharedPreferences(FLUTTER_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME, null)
        return MapTheme.from(raw).also { Log.i(TAG, "map theme: raw=$raw -> $it") }
    }

    fun mapsDir(): File = File(context.getExternalFilesDir(null), MAPS_DIR)

    /** The prepared template for [theme], from the module's assets. */
    fun template(theme: MapTheme): String =
        context.assets.open("styles/${theme.asset}.json").bufferedReader().use { it.readText() }

    /** Current theme, current packs, one style string — the frame loop's entry. */
    fun assembleCurrent(): DashStyle {
        val theme = theme()
        val packs = installedPacks()
        val style = MapStyleFactory.assemble(template(theme), packs)
        // N packs is N copies of the layer set — the number that quietly grows
        // the per-frame cost, so it belongs in the log next to the theme.
        Log.i(TAG, "style: $theme, ${packs.size} packs, ${style.length / 1024} KiB")
        return DashStyle(style, theme, packs.size)
    }

    private companion object {
        const val TAG = "MapStyleAssembler"
        const val MAPS_DIR = "maps"
        const val PACK_SUFFIX = ".pmtiles"
        const val FLUTTER_PREFS = "FlutterSharedPreferences"
        const val KEY_THEME = "flutter.map_theme"
    }
}

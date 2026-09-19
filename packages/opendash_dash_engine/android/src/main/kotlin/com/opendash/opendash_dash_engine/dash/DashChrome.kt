package com.opendash.opendash_dash_engine.dash

/**
 * The turn-by-turn figures the dash shows in its instruction bubble.
 *
 * Null [DashChrome.nav] means "no guidance running", which is a different thing from a
 * destination being set: the rider can have a destination on the card while the route is
 * still being built. The old code spelled that as a `navActive` boolean next to six loose
 * fields, and the boolean could be true while the figures belonged to a previous route.
 */
internal data class NavFigures(
    val maneuver: Int,
    val primaryDist: Int,
    val primaryUnit: Int,
    val totalDist: Int,
    val totalUnit: Int,
    val etaHHMM: String? = null,
)

/** Title/album/artist of whatever is playing, as the dash's media card wants them. */
internal data class NowPlaying(
    val title: String,
    val album: String = "",
    val artist: String = "",
)

/**
 * Everything the periodic senders read, as one value that outlives any single session.
 *
 * This is the other half of making [DashSession] one-shot. The keep-alives repeat a route
 * card, nav figures and a media card once a second, and those values come from Flutter at
 * arbitrary times — including while no session exists at all. Before, they were nine
 * `@Volatile` fields ON the session, so every reconnect started from defaults and the first
 * card of a new session showed "OpenDash" until Dart happened to push again.
 *
 * Held by [com.opendash.opendash_dash_engine.DashEngineController] in a `MutableStateFlow`
 * and read — not written — by the session. Same reasoning as `DashInputs` for the frame
 * loop: one immutable snapshot instead of loose fields, so a tick that sends a route card
 * and nav figures in the same pass sends figures that belong together.
 */
internal data class DashChrome(
    /**
     * The card's title. Blank is not representable — the dash shows this string, and an
     * empty one reads on the hardware as a card with no destination at all.
     */
    val destinationName: String = "OpenDash",
    val nav: NavFigures? = null,
    val nowPlaying: NowPlaying? = null,
    val caller: String? = null,
)

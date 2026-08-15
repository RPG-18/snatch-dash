import 'geo_point.dart';

/// Turn types a route can report — mirrors the Yandex MapKit SDK's
/// `DrivingAction` without depending on it (same pattern as [JamLevel] below
/// mirrors `JamType`); see `nav/router.dart` for the SDK-facing conversion.
/// [arrive] is synthetic — not from the SDK — appended as the guaranteed
/// final maneuver of every route.
enum ManeuverType {
  straight,
  slightLeft,
  slightRight,
  left,
  right,
  hardLeft,
  hardRight,
  forkLeft,
  forkRight,
  uturnLeft,
  uturnRight,
  enterRoundabout,
  leaveRoundabout,
  boardFerry,
  leaveFerry,
  exitLeft,
  exitRight,
  finish,
  waypoint,
  arrive,
}

/// Dash maneuver glyph bytes, keyed by [ManeuverType]. See
/// [`spec/glyph.md`](../../spec/glyph.md) for the full byte→icon table,
/// hardware-swept and confirmed against this port's physical dash — that
/// file is the source of truth; this map only carries the subset
/// [ManeuverType] can actually produce (Yandex's `DrivingAction` doesn't
/// have merge/fork/on-ramp/lane-keep granularity, so those glyph table rows
/// have no [ManeuverType] to key off here). [ManeuverType.arrive] and
/// [ManeuverType.finish] both map to `0x00` (destination, straight ahead).
/// Roundabout types aren't here — see [Maneuver.dashCode] and
/// [_roundaboutDashCode], they need [Maneuver.roundaboutClockwise]/
/// [Maneuver.roundaboutExitNumber] too, since the dash's roundabout glyph
/// depends on rotation direction and exit count, not [ManeuverType] alone.
///
/// Hardware-confirmed (2026-08-15, via the Dash screen's debug glyph probe):
/// every value below except [ManeuverType.forkLeft]/[ManeuverType.forkRight]/
/// [ManeuverType.exitLeft]/[ManeuverType.exitRight] (plausible from the
/// table's icon descriptions, not individually confirmed on this dash yet).
///
/// `0x09` (`straight`) is the dash's actual neutral glyph — NOT `0x0B`
/// (`0x0A` + exit 1) as the original open-dash project's inherited
/// `NAV_MANEUVER_CONTINUE` claimed; that claim is contradicted on this
/// dash/firmware (see `DashCommands.kt`'s glyph table for the full sweep).
const _dashCodeByType = {
  ManeuverType.straight: 0x09, // straight ahead
  ManeuverType.slightLeft: 0x18, // slight left
  ManeuverType.slightRight: 0x19, // slight right
  ManeuverType.left: 0x14, // turn left
  ManeuverType.right: 0x15, // turn right
  ManeuverType.hardLeft: 0x16, // sharp left
  ManeuverType.hardRight: 0x17, // sharp right
  ManeuverType.forkLeft: 0x06, // fork, left arrow blinks
  ManeuverType.forkRight: 0x05, // fork, right arrow blinks
  ManeuverType.uturnLeft: 0x3D, // U-turn, counterclockwise (→ left)
  ManeuverType.uturnRight: 0x1A, // U-turn, clockwise (→ right)
  ManeuverType.boardFerry: 0x3E, // ferry crossing
  ManeuverType.leaveFerry: 0x3E, // no distinct "leave" glyph in the table
  ManeuverType.exitLeft: 0x08, // exit onto a parallel road, left, 90°
  ManeuverType.exitRight: 0x07, // exit onto a parallel road, right, 90°
  ManeuverType.finish: 0x00, // destination, straight ahead
  ManeuverType.waypoint: 0x09, // no distinct glyph — treat like straight
  ManeuverType.arrive: 0x00, // destination (synthetic type, same as finish)
};

/// Roundabout glyph bases and per-exit offsets — see `spec/glyph.md`. The
/// byte isn't a flat `base + exit`: the sequence restarts in a different
/// range once the exit count hits 10 (confirmed for the clockwise row;
/// assumed by symmetry for counterclockwise, still unconfirmed).
const _roundaboutClockwiseBase = 0x0A; // + exit 1..9 → 0x0B..0x13
const _roundaboutClockwiseExit10Base = 0x46; // + (exit-10), 10..19 → 0x46..0x4F
const _roundaboutCounterclockwiseBase = 0x31; // + exit 1..9 → 0x32..0x3A — UNCONFIRMED
const _roundaboutCounterclockwiseExit10Base = 0x50; // + (exit-10), 10..19 → 0x50..0x59 — UNCONFIRMED

int _roundaboutDashCode(bool clockwise, int? exitNumber) {
  final base = clockwise ? _roundaboutClockwiseBase : _roundaboutCounterclockwiseBase;
  final exit10Base =
      clockwise ? _roundaboutClockwiseExit10Base : _roundaboutCounterclockwiseExit10Base;
  return switch (exitNumber) {
    null || 0 => base,
    >= 1 && <= 9 => base + exitNumber,
    >= 10 && <= 19 => exit10Base + (exitNumber - 10),
    _ => base, // beyond the confirmed range — fall back to the exit-unspecified icon
  };
}

/// One routing instruction located at a point along the geometry.
class Maneuver {
  const Maneuver({
    required this.type,
    required this.instruction,
    required this.location,
    required this.cumulativeMeters,
    this.roundaboutClockwise,
    this.roundaboutExitNumber,
  });

  final ManeuverType type;
  final String instruction;
  final GeoPoint location;

  /// Cumulative distance (m) from the route start to this maneuver's location.
  final double cumulativeMeters;

  /// Rotation sense for [ManeuverType.enterRoundabout]/[ManeuverType.leaveRoundabout]
  /// only — null for every other type, and also null when the router
  /// couldn't determine it (too few geometry points, see
  /// `Router._roundaboutClockwise`). Yandex's SDK has no field for this
  /// directly, but the dash's own glyph set needs it: [dashCode] picks
  /// between its clockwise/counterclockwise roundabout glyph bases off
  /// this flag.
  final bool? roundaboutClockwise;

  /// 1-based exit number, straight from Yandex's own
  /// `DrivingLeaveRoundaboutMetadata.exitNumber` — null when the SDK didn't
  /// supply one (e.g. not actually a roundabout maneuver, or metadata
  /// missing). [dashCode] feeds this into [_roundaboutDashCode] along with
  /// [roundaboutClockwise] to pick the exact roundabout glyph.
  final int? roundaboutExitNumber;

  /// Dash maneuver glyph byte — see [_dashCodeByType]/[_roundaboutDashCode]
  /// and this field's own caveats about verification. Roundabout types are
  /// handled separately (not in [_dashCodeByType]) since the glyph depends
  /// on [roundaboutClockwise]/[roundaboutExitNumber], not [type] alone.
  /// Falls back to `0x09` (the dash's confirmed neutral "straight ahead"
  /// glyph) when the rotation direction couldn't be computed at all, or for
  /// any [type] not covered by [_dashCodeByType].
  int get dashCode {
    if (type == ManeuverType.enterRoundabout || type == ManeuverType.leaveRoundabout) {
      final clockwise = roundaboutClockwise;
      if (clockwise == null) return 0x09;
      return _roundaboutDashCode(clockwise, roundaboutExitNumber);
    }
    return _dashCodeByType[type] ?? 0x09;
  }
}

/// Traffic intensity for a route geometry segment. Mirrors the Yandex
/// MapKit SDK's `JamType` without depending on it, so this stays a plain
/// domain model — see `nav/router.dart` for the SDK-facing conversion and
/// `spec/yande_ruote.md` for how this is drawn.
enum JamLevel { unknown, blocked, free, light, hard, veryHard }

/// A computed road route from origin to destination.
class Route {
  const Route({
    required this.geometry,
    required this.maneuvers,
    required this.totalMeters,
    required this.totalSeconds,
    required this.cumulative,
    this.jamSegments = const [],
  });

  final List<GeoPoint> geometry;
  final List<Maneuver> maneuvers;
  final double totalMeters;
  final double totalSeconds;

  /// Cumulative distance (m) at each geometry vertex — same length as [geometry].
  final List<double> cumulative;

  /// Traffic intensity per geometry segment — index i covers geometry[i] to
  /// geometry[i+1], so this has `geometry.length - 1` entries when present.
  /// Empty when traffic data wasn't available for this route.
  final List<JamLevel> jamSegments;

  GeoPoint? get destination => geometry.isEmpty ? null : geometry.last;
}

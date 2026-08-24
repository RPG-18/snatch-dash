import 'dart:async';

import 'package:flutter/widgets.dart' show WidgetsBinding;
import 'package:yandex_maps_mapkit/directions.dart' as ymk;
import 'package:yandex_maps_mapkit/mapkit.dart' as ymk;

import 'geo_point.dart';
import 'route.dart';

/// Fetches a driving route via the official Yandex MapKit SDK's driving
/// router — replaces the original app's OSRM-demo-server `Router.kt`. This
/// is a genuine win from the switch to Yandex MapKit (the original TODO
/// wanted off the unsupported public OSRM demo server).
///
/// [Route.maneuvers] is built from the route's per-section turn annotations
/// (`DrivingRoute.sections[i].metadata.annotation.action`) — real
/// turn-by-turn data, not the single synthetic ARRIVE this used to fall back
/// to before `sections` was wired up. Roundabout maneuvers also get a
/// computed [Maneuver.roundaboutClockwise] (see [_roundaboutClockwise])
/// since Yandex's SDK doesn't expose rotation direction directly but the
/// dash's glyph set needs it. Dash glyph bytes themselves (`Maneuver.dashCode`)
/// come from the real Royal Enfield app's maneuver table — not yet
/// hardware-verified against this port's dash beyond `0x0B`, see that
/// getter's doc.
///
/// The official SDK's router is listener-based, not `Future`-based like the
/// old `yandex_mapkit` community plugin — [route] wraps
/// `DrivingSessionRouteListener` in a `Completer` so call sites keep using
/// plain `await`.
class Router {
  Router._();

  static final _router =
      ymk.DirectionsFactory.instance.createDrivingRouter(ymk.DrivingRouterType.Online);

  /// Keeps in-flight [ymk.DrivingSession]s reachable — see the comment in
  /// [routes] for why this is necessary.
  static final _pending = <ymk.DrivingSession>{};

  static const _jamLevelByType = {
    ymk.JamType.Unknown: JamLevel.unknown,
    ymk.JamType.Blocked: JamLevel.blocked,
    ymk.JamType.Free: JamLevel.free,
    ymk.JamType.Light: JamLevel.light,
    ymk.JamType.Hard: JamLevel.hard,
    ymk.JamType.VeryHard: JamLevel.veryHard,
  };

  /// Matches [deviceLocalizations]'s locale resolution (OS locale, ru/en
  /// supported, no in-app switcher) so the SDK-generated
  /// `annotation.descriptionText` [VoiceManager] speaks comes back in the
  /// same language as the rest of the UI.
  static ymk.AnnotationLanguage get _annotationLanguage =>
      WidgetsBinding.instance.platformDispatcher.locale.languageCode == 'ru'
          ? ymk.AnnotationLanguage.Russian
          : ymk.AnnotationLanguage.English;

  static const _maneuverTypeByAction = {
    ymk.DrivingAction.Straight: ManeuverType.straight,
    ymk.DrivingAction.SlightLeft: ManeuverType.slightLeft,
    ymk.DrivingAction.SlightRight: ManeuverType.slightRight,
    ymk.DrivingAction.Left: ManeuverType.left,
    ymk.DrivingAction.Right: ManeuverType.right,
    ymk.DrivingAction.HardLeft: ManeuverType.hardLeft,
    ymk.DrivingAction.HardRight: ManeuverType.hardRight,
    ymk.DrivingAction.ForkLeft: ManeuverType.forkLeft,
    ymk.DrivingAction.ForkRight: ManeuverType.forkRight,
    ymk.DrivingAction.UturnLeft: ManeuverType.uturnLeft,
    ymk.DrivingAction.UturnRight: ManeuverType.uturnRight,
    ymk.DrivingAction.EnterRoundabout: ManeuverType.enterRoundabout,
    ymk.DrivingAction.LeaveRoundabout: ManeuverType.leaveRoundabout,
    ymk.DrivingAction.BoardFerry: ManeuverType.boardFerry,
    ymk.DrivingAction.LeaveFerry: ManeuverType.leaveFerry,
    ymk.DrivingAction.ExitLeft: ManeuverType.exitLeft,
    ymk.DrivingAction.ExitRight: ManeuverType.exitRight,
    ymk.DrivingAction.Finish: ManeuverType.finish,
    ymk.DrivingAction.Waypoint: ManeuverType.waypoint,
  };

  static Future<Route?> route(GeoPoint from, GeoPoint to) async {
    final results = await routes(from, to);
    if (results == null || results.isEmpty) return null;
    return results.first;
  }

  /// Requests up to [routesCount] alternative routes — used by the route
  /// preview screen to let the rider pick among them. [route] above is a
  /// thin single-result wrapper around this.
  static Future<List<Route>?> routes(GeoPoint from, GeoPoint to, {int routesCount = 1}) async {
    final completer = Completer<List<ymk.DrivingRoute>>();
    // Kept alive in `_pending` for the request's duration: `DrivingSession`
    // implements `Finalizable`, so if nothing on the Dart side references it,
    // the GC can collect it (and cancel the native request) before either
    // callback fires — the request then hangs forever with no error.
    final session = _router.requestRoutes(
      ymk.DrivingOptions(routesCount: routesCount, annotationLanguage: _annotationLanguage),
      const ymk.DrivingVehicleOptions(),
      ymk.DrivingSessionRouteListener(
        onDrivingRoutes: (routes) => completer.complete(routes),
        onDrivingRoutesError: (error) => completer.completeError(error),
      ),
      points: [
        ymk.RequestPoint(
          ymk.Point(latitude: from.lat, longitude: from.lng),
          ymk.RequestPointType.Waypoint,
          null,
          null,
          null,
        ),
        ymk.RequestPoint(
          ymk.Point(latitude: to.lat, longitude: to.lng),
          ymk.RequestPointType.Waypoint,
          null,
          null,
          null,
        ),
      ],
    );
    _pending.add(session);

    final List<ymk.DrivingRoute> raw;
    try {
      raw = await completer.future;
    } catch (_) {
      return null;
    } finally {
      _pending.remove(session);
    }
    if (raw.isEmpty) return null;
    return raw.map(_toRoute).whereType<Route>().toList();
  }

  static Route? _toRoute(ymk.DrivingRoute r) {
    final geometry = r.geometry.points.map((p) => GeoPoint(p.latitude, p.longitude)).toList();
    if (geometry.length < 2) return null;

    final cumulative = List<double>.filled(geometry.length, 0.0);
    for (var i = 1; i < geometry.length; i++) {
      cumulative[i] = cumulative[i - 1] + GeoPoint.distMeters(geometry[i - 1], geometry[i]);
    }

    final weight = r.metadata.weight;

    final maneuvers = [
      // Section 0's annotation describes the route's initial heading, not a
      // turn the rider needs a heads-up for — every section after that is a
      // real manoeuvre-to-manoeuvre leg (see `DrivingRoute.sections` doc).
      ...r.sections.skip(1).map((s) => _toManeuver(s, geometry, cumulative)).whereType<Maneuver>(),
      Maneuver(
        // Synthetic — not from a `DrivingSection.metadata.annotation`, so
        // there's no SDK `descriptionText` to put here. Left untranslated
        // deliberately: `VoiceManager._turnPhrase` special-cases `arrive`
        // and speaks its own l10n phrase instead of this field, and nothing
        // else reads `Maneuver.instruction`.
        type: ManeuverType.arrive,
        instruction: 'Arrive at destination',
        location: geometry.last,
        cumulativeMeters: weight.distance.value,
      ),
    ];

    return Route(
      geometry: geometry,
      maneuvers: maneuvers,
      totalMeters: weight.distance.value,
      totalSeconds: weight.timeWithTraffic.value,
      cumulative: cumulative,
      jamSegments: r.jamSegments
          .map((j) => _jamLevelByType[j.jamType] ?? JamLevel.unknown)
          .toList(),
    );
  }

  /// A section's turn happens at its geometry's start — [ymk.PolylinePosition]
  /// indexes into the *route's* geometry/[cumulative] arrays (sections are
  /// slices of the same overall polyline), so this interpolates the exact
  /// point and distance instead of snapping to the nearest vertex. Returns
  /// null for a malformed/out-of-range position rather than throwing —
  /// dropping one intermediate turn beats losing the whole route.
  static Maneuver? _toManeuver(
    ymk.DrivingSection section,
    List<GeoPoint> geometry,
    List<double> cumulative,
  ) {
    final pos = section.geometry.begin;
    final i = pos.segmentIndex;
    if (i < 0 || i >= geometry.length - 1) return null;

    final a = geometry[i];
    final b = geometry[i + 1];
    final t = pos.segmentPosition.clamp(0.0, 1.0);
    final annotation = section.metadata.annotation;
    final type = _maneuverTypeByAction[annotation.action] ?? ManeuverType.straight;

    final isRoundabout =
        type == ManeuverType.enterRoundabout || type == ManeuverType.leaveRoundabout;

    return Maneuver(
      type: type,
      instruction: annotation.descriptionText,
      location: GeoPoint(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t),
      cumulativeMeters: cumulative[i] + t * (cumulative[i + 1] - cumulative[i]),
      roundaboutClockwise: isRoundabout
          ? _roundaboutClockwise(geometry, section.geometry.begin, section.geometry.end)
          : null,
      roundaboutExitNumber:
          isRoundabout ? annotation.actionMetadata?.asLeaveRoundaboutMetadata()?.exitNumber : null,
    );
  }

  /// Net rotation sense of a roundabout traversal, read off the section's
  /// own geometry slice — a section's geometry starts where its maneuver
  /// happens and ends where the next one begins (`DrivingRoute.sections`'
  /// doc: "manoeuvre-to-manoeuvre"), so for a section whose action IS the
  /// roundabout entry/exit, that slice literally traces the arc actually
  /// driven around the roundabout.
  ///
  /// Sums the signed bearing change at each interior vertex of the slice:
  /// continuously turning right traces a clockwise arc (bearing keeps
  /// increasing), continuously turning left traces counterclockwise — and
  /// that's exactly how roundabout direction correlates with traffic side
  /// (right-hand traffic circles counterclockwise, left-hand clockwise), so
  /// no separate "which country" input is needed. Returns null if the slice
  /// is too short (fewer than 2 interior segments) to tell — callers fall
  /// back to the verified-safe glyph rather than guess.
  static bool? _roundaboutClockwise(
    List<GeoPoint> geometry,
    ymk.PolylinePosition begin,
    ymk.PolylinePosition end,
  ) {
    final from = begin.segmentIndex.clamp(0, geometry.length - 1);
    final to = (end.segmentIndex + 1).clamp(0, geometry.length - 1);
    if (to - from < 2) return null;

    var sum = 0.0;
    var prevBearing = GeoPoint.bearing(geometry[from], geometry[from + 1]);
    for (var i = from + 1; i < to; i++) {
      final bearing = GeoPoint.bearing(geometry[i], geometry[i + 1]);
      final delta = ((bearing - prevBearing + 180) % 360 + 360) % 360 - 180;
      sum += delta;
      prevBearing = bearing;
    }
    return sum > 0;
  }
}

import 'package:flutter/material.dart';
import 'package:yandex_maps_mapkit/mapkit.dart' as ymk;
import 'package:yandex_maps_mapkit/yandex_map.dart' as ymk_widget;

import '../nav/geo_point.dart';
import '../nav/route.dart' show JamLevel;
import 'jam_paint.dart';

const _followZoom = 15.5;
const _navZoom = 17.5;
const _navTilt = 45.0;

const _routeColor = Color(0xFF4285F4);

/// In-app phone map — `yandex_maps_mapkit` (official Yandex MapKit SDK)
/// replacement for the original app's MapLibre/OpenFreeMap `OpenDashMap.kt`.
/// The physical dash still uses its own off-screen power-efficient renderer
/// (native, see `TileProvider`/`MapRenderer` — deliberately NOT this widget's
/// tile source).
///
/// Modes: [fitRoute] frames the whole route; [navMode] tilts/zooms/rotates to
/// heading; default follows the rider north-up.
///
/// Unlike the old `yandex_mapkit` plugin (declarative `mapObjects:` list,
/// diffed by mapId), the official SDK's map objects are imperative — you
/// mutate a `RootMapObjectCollection` directly. [_syncObjects] clears and
/// rebuilds it whenever the relevant inputs change, driven by
/// [didUpdateWidget] rather than every `build()`.
class OpenDashMap extends StatefulWidget {
  const OpenDashMap({
    super.key,
    this.riderLat,
    this.riderLng,
    this.dest,
    this.routePoints = const [],
    this.jamSegments = const [],
    this.fitRoute = false,
    this.navMode = false,
    this.riderBearing = 0,
  });

  final double? riderLat;
  final double? riderLng;
  final GeoPoint? dest;
  final List<GeoPoint> routePoints;

  /// Traffic intensity per [routePoints] segment — see `nav.Route.jamSegments`.
  /// Colored per-segment when its length matches `routePoints.length - 1`;
  /// falls back to a flat color otherwise (e.g. traffic data unavailable).
  final List<JamLevel> jamSegments;
  final bool fitRoute;
  final bool navMode;
  final double riderBearing;

  @override
  State<OpenDashMap> createState() => _OpenDashMapState();
}

class _OpenDashMapState extends State<OpenDashMap> {
  ymk.MapWindow? _mapWindow;

  @override
  void didUpdateWidget(covariant OpenDashMap old) {
    super.didUpdateWidget(old);
    if (old.riderLat != widget.riderLat ||
        old.riderLng != widget.riderLng ||
        old.riderBearing != widget.riderBearing ||
        old.navMode != widget.navMode ||
        old.fitRoute != widget.fitRoute ||
        old.routePoints.length != widget.routePoints.length ||
        old.jamSegments.length != widget.jamSegments.length ||
        old.dest != widget.dest) {
      _syncObjects();
      _moveCamera();
    }
  }

  @override
  Widget build(BuildContext context) {
    return ymk_widget.YandexMap(
      onMapCreated: (window) {
        _mapWindow = window;
        _syncObjects();
        _moveCamera();
      },
    );
  }

  void _syncObjects() {
    final window = _mapWindow;
    if (window == null) return;
    final objects = window.map.mapObjects;
    objects.clear();

    if (widget.routePoints.length >= 2) {
      final line = objects.addPolylineWithGeometry(
        ymk.Polyline(
          widget.routePoints.map((p) => ymk.Point(latitude: p.lat, longitude: p.lng)).toList(),
        ),
      );
      line.style = const ymk.LineStyle(strokeWidth: 5.5);
      applyJamColors(line, widget.jamSegments, _routeColor);
    }

    final dest = widget.dest;
    if (dest != null) {
      objects.addPlacemarkWithPoint(ymk.Point(latitude: dest.lat, longitude: dest.lng));
    }

    if (widget.navMode && widget.riderLat != null && widget.riderLng != null) {
      final rider = objects.addPlacemarkWithPoint(
        ymk.Point(latitude: widget.riderLat!, longitude: widget.riderLng!),
      );
      rider.direction = widget.riderBearing;
    }
  }

  void _moveCamera() {
    final window = _mapWindow;
    if (window == null) return;
    final map = window.map;
    const animation = ymk.Animation(type: ymk.AnimationType.Smooth, duration: 0.6);

    if (widget.fitRoute && widget.routePoints.length >= 2) {
      var minLat = widget.routePoints.first.lat, maxLat = widget.routePoints.first.lat;
      var minLng = widget.routePoints.first.lng, maxLng = widget.routePoints.first.lng;
      for (final p in widget.routePoints) {
        if (p.lat < minLat) minLat = p.lat;
        if (p.lat > maxLat) maxLat = p.lat;
        if (p.lng < minLng) minLng = p.lng;
        if (p.lng > maxLng) maxLng = p.lng;
      }
      final bounds = ymk.BoundingBox(
        ymk.Point(latitude: minLat, longitude: minLng),
        ymk.Point(latitude: maxLat, longitude: maxLng),
      );
      final position = map.cameraPositionForGeometry(ymk.Geometry.fromBoundingBox(bounds));
      map.move(position, animation: animation);
      return;
    }

    if (widget.riderLat != null && widget.riderLng != null) {
      final target = ymk.Point(latitude: widget.riderLat!, longitude: widget.riderLng!);
      final position = widget.navMode
          ? ymk.CameraPosition(target, zoom: _navZoom, azimuth: widget.riderBearing, tilt: _navTilt)
          : ymk.CameraPosition(target, zoom: _followZoom, azimuth: 0, tilt: 0);
      map.move(position, animation: animation);
      return;
    }

    final dest = widget.dest;
    if (dest != null) {
      map.move(
        ymk.CameraPosition(ymk.Point(latitude: dest.lat, longitude: dest.lng), zoom: 13, azimuth: 0, tilt: 0),
      );
    }
  }
}

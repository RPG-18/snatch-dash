import 'package:flutter/material.dart';
import 'package:yandex_maps_mapkit/mapkit.dart' as ymk;
import 'package:yandex_maps_mapkit/yandex_map.dart' as ymk_widget;

import '../nav/geo_point.dart';
import '../nav/route.dart' as nav;
import 'jam_paint.dart';

const _selectedColor = Color(0xFF34A853);
const _unselectedColor = Color(0xFF9C27B0);
const _boundsPadding = 0.08;
const _lineWidth = 5.5;

// Explicit paint order. Alternative routes share most of their geometry with
// the selected one, and MapKit gives no stable order to objects that sit at
// the same z-index — so without this the purple alternatives can come out on
// top of the green selected route, in whole or in part.
const _unselectedZ = 0.0;
const _selectedZ = 1.0;

// Palette slots for the two flat (non-traffic) colors, past the ones
// [JamLevel] occupies. These lines are restyled in place rather than
// recreated, so they must never be painted with `setStrokeColor`: that
// "effectively sets a single-color palette", dropping the jam slots for good.
// A line that was flattened and then selected again came back gray (slot 0,
// the one that survived) and blue (MapKit's 0x0066FFFF default for slots with
// no color). Every slot is primed once, at creation, and from then on only
// `setStrokeColors` runs — it reindexes segments without touching the palette.
const _flatSelectedSlot = 6; // == JamLevel.values.length
const _flatUnselectedSlot = 7;

/// Map for the route preview screen — draws every alternative route, framed
/// to fit them all. The selected route is drawn on top, colored by traffic
/// intensity (falling back to green when jam data is unavailable); the rest
/// stay flat purple. See spec/yande_ruote.md and [OpenDashMap] for the
/// single-route dash map counterpart.
class RouteOptionsMap extends StatefulWidget {
  const RouteOptionsMap({
    super.key,
    required this.routes,
    required this.selectedIndex,
    required this.onRouteTap,
    this.origin,
    this.destination,
  });

  final List<nav.Route> routes;
  final int selectedIndex;
  final ValueChanged<int> onRouteTap;
  final GeoPoint? origin;
  final GeoPoint? destination;

  @override
  State<RouteOptionsMap> createState() => _RouteOptionsMapState();
}

class _RouteOptionsMapState extends State<RouteOptionsMap> {
  ymk.MapWindow? _mapWindow;

  /// One polyline per entry of [RouteOptionsMap.routes], in the same order.
  /// Kept so that switching the selection only restyles these objects — see
  /// [_applySelection].
  final List<ymk.PolylineMapObject> _lines = [];

  /// Tap listeners for [_lines], kept alive here: MapKit only holds a
  /// [WeakReference] to each one internally, so a listener with nothing
  /// else pointing at it on the Dart side gets collected and taps on its
  /// polyline start throwing instead of firing.
  final List<_RouteTapListener> _tapListeners = [];

  @override
  void didUpdateWidget(covariant RouteOptionsMap old) {
    super.didUpdateWidget(old);
    if (!identical(old.routes, widget.routes)) {
      _rebuildObjects();
      _fitBounds();
    } else if (old.selectedIndex != widget.selectedIndex) {
      _applySelection();
    }
  }

  @override
  Widget build(BuildContext context) {
    return ymk_widget.YandexMap(
      onMapCreated: (window) {
        _mapWindow = window;
        _rebuildObjects();
        _fitBounds();
      },
    );
  }

  /// Rebuilds the whole collection — only when the route list itself changes.
  /// A selection change must NOT come through here: `clear()` drops every
  /// polyline and the re-add that follows is a separate call, so the map's
  /// render thread can sample the collection while it is empty or half
  /// filled, which is what made routes flicker out on each switch.
  void _rebuildObjects() {
    final window = _mapWindow;
    if (window == null) return;
    final objects = window.map.mapObjects;
    objects.clear();
    _lines.clear();
    _tapListeners.clear();

    for (var i = 0; i < widget.routes.length; i++) {
      final line = objects.addPolylineWithGeometry(_geometry(widget.routes[i]));
      line.style = const ymk.LineStyle(strokeWidth: _lineWidth);
      for (final level in nav.JamLevel.values) {
        line.setPaletteColor(jamColors[level]!, colorIndex: level.index);
      }
      line.setPaletteColor(_selectedColor, colorIndex: _flatSelectedSlot);
      line.setPaletteColor(_unselectedColor, colorIndex: _flatUnselectedSlot);
      final listener = _RouteTapListener(() => widget.onRouteTap(i));
      line.addTapListener(listener);
      _tapListeners.add(listener);
      _lines.add(line);
    }
    _applySelection();

    final origin = widget.origin;
    if (origin != null) {
      objects.addPlacemarkWithPoint(ymk.Point(latitude: origin.lat, longitude: origin.lng));
    }
    final dest = widget.destination;
    if (dest != null) {
      objects.addPlacemarkWithPoint(ymk.Point(latitude: dest.lat, longitude: dest.lng));
    }
  }

  /// Repaints the existing polylines for the current selection: the selected
  /// route traffic-colored and on top, the rest flat purple below it. Only
  /// segment→slot indices are reassigned; the palette primed in
  /// [_rebuildObjects] stays intact, so this is reversible any number of times.
  void _applySelection() {
    if (_lines.length != widget.routes.length) return;
    for (var i = 0; i < _lines.length; i++) {
      final line = _lines[i];
      final segments = line.geometry.points.length - 1;
      if (segments < 1) continue;
      final selected = i == widget.selectedIndex;
      line.zIndex = selected ? _selectedZ : _unselectedZ;

      // Traffic colors only for the selected route, and only when jam data
      // covers every segment — otherwise it falls back to a flat color, same
      // rule as [applyJamColors] applies on the dash map.
      final jam = widget.routes[i].jamSegments;
      line.setStrokeColors(
        selected && jam.length == segments
            ? [for (final level in jam) level.index]
            : List.filled(segments, selected ? _flatSelectedSlot : _flatUnselectedSlot),
      );
    }
    // Restyling the polylines alone doesn't reach the screen: this native
    // view only composites a new frame off the back of a camera change, so
    // without this the old colors visibly stick around until the rider
    // happens to touch the map (any gesture forces the missing frame).
    // A same-position, no-animation move is the cheapest way to trigger one.
    final window = _mapWindow;
    if (window != null) window.map.move(window.map.cameraPosition);
  }

  ymk.Polyline _geometry(nav.Route route) =>
      ymk.Polyline(route.geometry.map((p) => ymk.Point(latitude: p.lat, longitude: p.lng)).toList());

  void _fitBounds() {
    final window = _mapWindow;
    if (window == null || widget.routes.isEmpty) return;
    final points = widget.routes.expand((r) => r.geometry).toList();
    if (points.isEmpty) return;

    var minLat = points.first.lat, maxLat = points.first.lat;
    var minLng = points.first.lng, maxLng = points.first.lng;
    for (final p in points) {
      if (p.lat < minLat) minLat = p.lat;
      if (p.lat > maxLat) maxLat = p.lat;
      if (p.lng < minLng) minLng = p.lng;
      if (p.lng > maxLng) maxLng = p.lng;
    }
    // 8% margin on each side so routes don't touch the viewport edges.
    final latPad = (maxLat - minLat) * _boundsPadding;
    final lngPad = (maxLng - minLng) * _boundsPadding;
    final bounds = ymk.BoundingBox(
      ymk.Point(latitude: minLat - latPad, longitude: minLng - lngPad),
      ymk.Point(latitude: maxLat + latPad, longitude: maxLng + lngPad),
    );
    final position = window.map.cameraPositionForGeometry(ymk.Geometry.fromBoundingBox(bounds));
    window.map.move(
      position,
      animation: const ymk.Animation(type: ymk.AnimationType.Smooth, duration: 0.6),
    );
  }
}

class _RouteTapListener implements ymk.MapObjectTapListener {
  _RouteTapListener(this._onTap);

  final VoidCallback _onTap;

  @override
  bool onMapObjectTap(ymk.MapObject mapObject, ymk.Point point) {
    _onTap();
    return true;
  }
}

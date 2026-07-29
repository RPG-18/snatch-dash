import 'package:flutter/material.dart';
import 'package:yandex_maps_mapkit/mapkit.dart' as ymk;

import '../nav/route.dart';

/// Default jam-type palette — mirrors the Yandex MapKit SDK's own
/// `RouteHelper.createDefaultJamStyle()` colors, see spec/yande_ruote.md.
const jamColors = {
  JamLevel.unknown: Color(0xFF909090),
  JamLevel.blocked: Color(0xFF000000),
  JamLevel.free: Color(0xFF00FF00),
  JamLevel.light: Color(0xFFFFFF00),
  JamLevel.hard: Color(0xFFFF0000),
  JamLevel.veryHard: Color(0xFFA00000),
};

/// Colors [line] per-segment by traffic intensity when [jamSegments] covers
/// every geometry segment; otherwise falls back to a flat [fallbackColor]
/// (e.g. traffic data wasn't available for this route). See
/// spec/yande_ruote.md for the color table.
///
/// For lines that get recreated on every change — `OpenDashMap`'s single
/// route. The flat path calls `setStrokeColor`, which collapses the palette
/// to one slot, so a line that has to switch back to traffic colors later
/// must not go through here; `RouteOptionsMap` primes [jamColors] once per
/// polyline and reindexes with `setStrokeColors` instead.
void applyJamColors(ymk.PolylineMapObject line, List<JamLevel> jamSegments, Color fallbackColor) {
  final segmentCount = line.geometry.points.length - 1;
  if (jamSegments.length != segmentCount) {
    line.setStrokeColor(fallbackColor);
    return;
  }
  for (final level in JamLevel.values) {
    line.setPaletteColor(jamColors[level]!, colorIndex: level.index);
  }
  line.setStrokeColors(jamSegments.map((level) => level.index).toList());
}

import 'dart:async';

import 'package:yandex_maps_mapkit/mapkit.dart' as ymk;
import 'package:yandex_maps_mapkit/search.dart' as ymk;

import '../util/app_logger.dart';
import 'geo_point.dart';
import 'place.dart';

/// Search center used when no GPS fix is available yet (central Moscow) —
/// only biases result ranking, doesn't affect [PlaceResult.distanceMeters].
const _fallbackOrigin = GeoPoint(55.751244, 37.618423);

/// Text search for destinations via the official Yandex MapKit SDK's
/// `SearchManager`. [RouteScreen]'s live "куда" list is driven by the Yandex
/// Geosuggest HTTP API instead (`SuggestApi`) — that endpoint returns no
/// coordinates, so picking a suggestion resolves it to one or more candidate
/// [PlaceResult]s here (same name can mean several real places, e.g. a chain
/// store — the caller lets the rider disambiguate when there's more than one).
///
/// The official SDK's search is listener-based, not `Future`-based like the
/// old `yandex_mapkit` community plugin — [search] wraps
/// `SearchSessionSearchListener` in a `Completer` so call sites keep using
/// plain `await`.
class PlaceSearch {
  PlaceSearch._();

  // "Online" only — "Offline"/"Combined" search managers require a paid
  // MapKit tier (offline index), not available on the free key this app uses.
  static final _searchManager =
      ymk.SearchFactory.instance.createSearchManager(ymk.SearchManagerType.Online);

  static ymk.SearchSession? _session;

  /// Cancels any in-flight search request.
  static void cancel() {
    _session?.cancel();
    _session = null;
  }

  static Future<List<PlaceResult>> search(String text, {GeoPoint? near, int resultPageSize = 10}) async {
    cancel();
    talker.info('[PlaceSearch] query="$text"');

    final completer = Completer<ymk.SearchResponse>();
    final session = _searchManager.submit(
      ymk.Geometry.fromPoint(ymk.Point(latitude: _fallbackOrigin.lat, longitude: _fallbackOrigin.lng)),
      ymk.SearchOptions(
        searchTypes: ymk.SearchType.None,
        geometry: true,
        resultPageSize: resultPageSize,
      ),
      ymk.SearchSessionSearchListener(
        onSearchResponse: (response) => completer.complete(response),
        onSearchError: (error) => completer.completeError(error),
      ),
      text: text,
    );
    _session = session;

    final ymk.SearchResponse response;
    try {
      response = await completer.future;
    } catch (e) {
      talker.warning('[PlaceSearch] query="$text" failed: $e');
      return const [];
    }

    final results = <PlaceResult>[];
    for (final item in response.collection.children) {
      final geoObject = item.asGeoObject();
      if (geoObject == null) continue;

      final toponym = geoObject.metadataContainer.get(ymk.SearchToponymObjectMetadata.factory);
      final points = geoObject.geometry.map((g) => g.asPoint()).whereType<ymk.Point>();
      final point = toponym?.balloonPoint ?? (points.isEmpty ? null : points.first);
      if (point == null) continue;

      final dest = GeoPoint(point.latitude, point.longitude);
      results.add(PlaceResult(
        name: geoObject.name ?? '',
        address: toponym?.address.formattedAddress ?? '',
        point: dest,
        distanceMeters: near != null ? GeoPoint.distMeters(near, dest) : null,
      ));
    }
    talker.info('[PlaceSearch] query="$text" -> ${results.length} result(s)');
    return results;
  }
}

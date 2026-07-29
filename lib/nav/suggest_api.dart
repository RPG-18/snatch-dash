import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import '../util/app_logger.dart';
import 'geo_point.dart';
import 'suggest_result.dart';

// Set via `--dart-define-from-file=android/dart_defines.local.properties`
// (gitignored; see android/dart_defines.defaults.properties for the
// bring-your-own-key template). Same key as YANDEX_API_KEY in lib/main.dart
// — Yandex now issues one API key shared across its map products.
const _apiKey = String.fromEnvironment('YANDEX_API_KEY');

const _baseUrl = 'https://suggest-maps.yandex.ru/v1/suggest';
const _maxResults = 10;

/// The endpoint can flake and return 0 results (or a non-200) for a query
/// that has real matches — seen in practice, confirmed by re-issuing the
/// exact same request and getting anywhere from 0 to 7 results back. Retry
/// with exponential backoff instead of showing "nothing found" on the first
/// bad response.
const _maxAttempts = 5;
const _initialRetryDelay = Duration(milliseconds: 150);

/// How many recent normalized queries to keep cached, so retyping/backspacing
/// over a prefix already seen this session doesn't refire the network.
const _cacheCapacity = 16;

/// Live destination suggestions via the Yandex Geosuggest HTTP API
/// (api/openapi/yandex-suggest-api.yaml), backing [RouteScreen]'s "куда"
/// search. Unlike [PlaceSearch] (on-device MapKit `SearchManager`), this
/// hits the network per query, so callers are expected to debounce —
/// [SuggestApi] itself only handles per-request cancellation and caching.
class SuggestApi {
  SuggestApi._();

  static final _client = http.Client();

  /// Simple LRU: insertion order = recency, oldest evicted first. Read hits
  /// re-insert to bump recency.
  static final _cache = <String, List<SuggestResult>>{};

  /// Cancels the in-flight request started by [suggest], if any. Safe to
  /// call when nothing is in flight.
  static void cancel() {
    if (!(_abort?.isCompleted ?? true)) _abort!.complete();
  }

  static Completer<void>? _abort;

  static Future<List<SuggestResult>> suggest(String text, {GeoPoint? near}) async {
    final key = text.trim().toLowerCase();

    final cached = _cache.remove(key);
    if (cached != null) {
      _cache[key] = cached; // bump recency
      return cached;
    }

    cancel();
    final abort = Completer<void>();
    _abort = abort;

    var delay = _initialRetryDelay;
    var results = const <SuggestResult>[];
    for (var attempt = 1; attempt <= _maxAttempts; attempt++) {
      try {
        results = await _fetchOnce(text, near, abort.future, attempt);
      } on http.RequestAbortedException {
        // Superseded by a newer query — not a real failure, caller ignores
        // this; retrying would just fight the query that took over.
        rethrow;
      } catch (e) {
        if (attempt == _maxAttempts) {
          talker.warning('[SuggestApi] query="$text" failed after $attempt attempt(s): $e');
          rethrow;
        }
        talker.warning('[SuggestApi] query="$text" attempt $attempt failed: $e — retrying');
        results = const [];
      }

      if (results.isNotEmpty || attempt == _maxAttempts) break;

      // Empty on a non-final attempt — flaky upstream response for a query
      // that may well have real matches; back off and retry rather than
      // showing "nothing found".
      await Future.any([Future<void>.delayed(delay), abort.future]);
      if (abort.isCompleted) throw http.RequestAbortedException(Uri.parse(_baseUrl));
      delay *= 2;
    }

    _cache[key] = results;
    if (_cache.length > _cacheCapacity) {
      _cache.remove(_cache.keys.first);
    }
    return results;
  }

  static Future<List<SuggestResult>> _fetchOnce(
    String text,
    GeoPoint? near,
    Future<void> abortTrigger,
    int attempt,
  ) async {
    final uri = Uri.parse(_baseUrl).replace(queryParameters: {
      'apikey': _apiKey,
      'text': text,
      'results': '$_maxResults',
      'print_address': '1',
      if (near != null) 'ull': '${near.lng},${near.lat}',
    });

    talker.info('[SuggestApi] query="$text" (attempt $attempt)');
    final request = http.AbortableRequest('GET', uri, abortTrigger: abortTrigger);
    final streamed = await _client.send(request);
    final response = await http.Response.fromStream(streamed);

    if (response.statusCode != 200) {
      talker.warning('[SuggestApi] query="$text" (attempt $attempt) -> HTTP ${response.statusCode}');
      return const [];
    }

    final body = jsonDecode(utf8.decode(response.bodyBytes)) as Map<String, dynamic>;
    final results = (body['results'] as List<dynamic>? ?? const [])
        .map((e) => SuggestResult.fromJson(e as Map<String, dynamic>))
        .toList(growable: false);
    talker.info('[SuggestApi] query="$text" (attempt $attempt) -> ${results.length} result(s)');
    return results;
  }
}

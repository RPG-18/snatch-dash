import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:http/http.dart' as http;

import '../nav/geo_point.dart';
import '../nav/place.dart';
import '../nav/place_search.dart';
import '../nav/suggest_api.dart';
import '../nav/suggest_result.dart';
import '../util/app_logger.dart';
import '../util/current_position.dart';

/// Minimum query length before a live search fires, per the Route screen spec.
const _minQueryLength = 3;
const _debounceDelay = Duration(milliseconds: 300);

class RouteSearchState {
  const RouteSearchState({
    this.query = '',
    this.results = const [],
    this.searching = false,
    this.error = false,
    this.resolving = false,
    this.origin,
  });

  final String query;
  final List<SuggestResult> results;
  final bool searching;
  final bool error;

  /// True while a picked suggestion is being resolved to coordinates
  /// (see [RouteSearchController.resolve]).
  final bool resolving;
  final GeoPoint? origin;

  RouteSearchState copyWith({
    String? query,
    List<SuggestResult>? results,
    bool? searching,
    bool error = false,
    bool? resolving,
    GeoPoint? origin,
  }) {
    return RouteSearchState(
      query: query ?? this.query,
      results: results ?? this.results,
      searching: searching ?? this.searching,
      error: error,
      resolving: resolving ?? this.resolving,
      origin: origin ?? this.origin,
    );
  }
}

/// Live destination search backing [RouteScreen] — debounced text search via
/// [SuggestApi] (Yandex Geosuggest HTTP API), auto-updating as the rider
/// types. The suggest endpoint returns no coordinates by design, so picking a
/// result goes through [resolve] first; only then does the screen hand
/// coordinates off to `RoutePreviewScreen`. This controller only owns the
/// search box, not the picked destination (that's [RouteController]).
class RouteSearchController extends Notifier<RouteSearchState> {
  Timer? _debounceTimer;
  int _requestId = 0;

  /// Same idea as [_requestId], for [resolve]: two overlapping resolves (a quick
  /// second tap on a suggestion) both clear `resolving` in their `finally`, so
  /// whichever finishes first hides the spinner while the other is still running.
  int _resolveId = 0;

  @override
  RouteSearchState build() {
    ref.onDispose(() {
      _debounceTimer?.cancel();
      SuggestApi.cancel();
    });
    unawaited(_loadOrigin());
    return const RouteSearchState();
  }

  Future<void> _loadOrigin() async {
    final origin = await currentPosition();
    // The provider can be disposed while the await above is pending —
    // writing `state` after that throws `UnmountedRefException`.
    if (!ref.mounted) return;
    state = state.copyWith(origin: origin);
  }

  void setQuery(String text) {
    _debounceTimer?.cancel();
    state = state.copyWith(query: text);

    if (text.trim().length < _minQueryLength) {
      SuggestApi.cancel();
      state = state.copyWith(results: const []);
      return;
    }

    _debounceTimer = Timer(_debounceDelay, () => _search(text));
  }

  Future<void> _search(String text) async {
    final requestId = ++_requestId;
    state = state.copyWith(searching: true);
    try {
      final results = await SuggestApi.suggest(text, near: state.origin);
      if (requestId != _requestId) return; // superseded by a newer query
      state = state.copyWith(results: results, searching: false);
    } on http.RequestAbortedException {
      // Cancelled in favor of a newer query — that query owns `searching` now.
    } catch (e, st) {
      talker.error('[RouteSearchController] search "$text" threw', e, st);
      if (requestId != _requestId) return;
      state = state.copyWith(results: const [], searching: false, error: true);
    }
  }

  /// Resolves a picked suggestion to candidate places with coordinates via
  /// [PlaceSearch.search] (the suggest endpoint itself never returns
  /// coordinates). Returns every match rather than just the first — the same
  /// name can be several real places (e.g. a chain store), so the screen is
  /// expected to let the rider disambiguate when there's more than one.
  Future<List<PlaceResult>> resolve(SuggestResult item) async {
    final resolveId = ++_resolveId;
    state = state.copyWith(resolving: true);
    try {
      return await PlaceSearch.search(item.resolveQuery, near: state.origin);
    } catch (e, st) {
      talker.error('[RouteSearchController] resolve "${item.resolveQuery}" threw', e, st);
      return const [];
    } finally {
      // Only the newest resolve owns the flag. `ref.mounted` for the usual
      // reason — this runs after an await, and the provider can be gone by then,
      // which would make the `finally` throw `UnmountedRefException` over
      // whatever the caller was actually returning.
      if (resolveId == _resolveId && ref.mounted) {
        state = state.copyWith(resolving: false);
      }
    }
  }
}

final routeSearchControllerProvider =
    NotifierProvider<RouteSearchController, RouteSearchState>(RouteSearchController.new);

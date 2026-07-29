import 'dart:async' show unawaited;

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:opendash_dash_engine/opendash_dash_engine.dart';

import '../models/shared_location.dart';
import '../nav/geo_point.dart';
import '../nav/nav_loop.dart';
import '../nav/route.dart' as nav;
import '../nav/router.dart' as nav;
import '../util/current_position.dart';
import '../util/location_parser.dart';
import 'saved_destinations_controller.dart';

/// Kept as codes (not localized strings) since the controller has no
/// `BuildContext` — [RouteScreen] maps these to text via `AppLocalizations`.
enum RouteErrorKind { noCoordinates, noGpsFix, routingFailedConnection, routingFailedDetail }

class RouteError {
  const RouteError(this.kind, {this.detail});
  final RouteErrorKind kind;
  final String? detail;
}

class RouteState {
  const RouteState({
    this.queryText = '',
    this.resolving = false,
    this.destination,
    this.route,
    this.error,
  });

  final String queryText;
  final bool resolving;
  final SharedLocation? destination;
  final nav.Route? route;
  final RouteError? error;

  RouteState copyWith({
    String? queryText,
    bool? resolving,
    SharedLocation? destination,
    nav.Route? route,
    RouteError? error,
    bool clearDestination = false,
    bool clearRoute = false,
    bool clearError = false,
  }) {
    return RouteState(
      queryText: queryText ?? this.queryText,
      resolving: resolving ?? this.resolving,
      destination: clearDestination ? null : (destination ?? this.destination),
      route: clearRoute ? null : (route ?? this.route),
      error: clearError ? null : (error ?? this.error),
    );
  }
}

/// Destination search/preview + "Send to Dash" — the Dart-side counterpart
/// to the original `RouteViewModel.kt`. Ports `LocationParser` (share-text
/// parsing) and drives `Router` (Yandex driving) for the preview; a one-shot
/// `Geolocator` fix stands in for the original's separate `LocationManager`
/// use for route planning (independent of the dash session's own native GPS
/// tracking, which only starts once `DashEngine.connect()` is called).
class RouteController extends Notifier<RouteState> {
  NavLoop? _navLoop;

  @override
  RouteState build() {
    ref.onDispose(() => _navLoop?.stop());
    return const RouteState();
  }

  void setDestinationFromShareText(String text) {
    final parsed = LocationParser.parse(text);
    state = state.copyWith(
      queryText: text,
      destination: parsed,
      clearRoute: true,
      clearError: true,
    );
    unawaited(_resolveAndRoute(parsed));
  }

  void clear() {
    _navLoop?.stop();
    _navLoop = null;
    state = const RouteState();
  }

  /// Loads a previously saved destination straight from its coordinates
  /// (no re-parsing/network resolve needed) and kicks off route planning.
  void selectSaved(SavedLocation location) {
    final destination = SharedLocation(name: location.name, lat: location.lat, lng: location.lng);
    state = state.copyWith(destination: destination, clearRoute: true, clearError: true);
    unawaited(_fetchRoute(destination));
  }

  /// Adopts a destination + route already resolved elsewhere (the route
  /// preview screen's alternative picker) instead of fetching one, then
  /// [sendToDash] can be called right away.
  void selectRoute(SharedLocation destination, nav.Route route) {
    state = state.copyWith(destination: destination, route: route, clearError: true);
  }

  void saveCurrentDestination() {
    final d = state.destination;
    if (d?.lat == null || d?.lng == null) return;
    ref.read(savedDestinationsControllerProvider.notifier).save(d!.name, d.lat!, d.lng!);
  }

  Future<void> _resolveAndRoute(SharedLocation parsed) async {
    var destination = parsed;
    if (parsed.needsExpansion && parsed.url != null) {
      state = state.copyWith(resolving: true);
      final (coords, name) = await LocationParser.resolve(parsed.url!);
      destination = SharedLocation(
        name: name ?? parsed.name,
        lat: coords?.$1,
        lng: coords?.$2,
        url: parsed.url,
        needsExpansion: false,
      );
      state = state.copyWith(destination: destination, resolving: false);
    }

    if (destination.lat == null || destination.lng == null) {
      state = state.copyWith(error: const RouteError(RouteErrorKind.noCoordinates), resolving: false);
      return;
    }

    await _fetchRoute(destination);
  }

  Future<void> _fetchRoute(SharedLocation destination) async {
    state = state.copyWith(resolving: true, clearError: true);
    try {
      final origin = await currentPosition();
      if (origin == null) {
        state = state.copyWith(resolving: false, error: const RouteError(RouteErrorKind.noGpsFix));
        return;
      }
      final route = await nav.Router.route(
        origin,
        GeoPoint(destination.lat!, destination.lng!),
      );
      state = state.copyWith(route: route, resolving: false, clearError: route != null);
      if (route == null) {
        state = state.copyWith(error: const RouteError(RouteErrorKind.routingFailedConnection));
      }
    } catch (e) {
      state = state.copyWith(
        resolving: false,
        error: RouteError(RouteErrorKind.routingFailedDetail, detail: '$e'),
      );
    }
  }

  /// "Send to Dash": hands the destination + route to the native engine and
  /// starts the nav loop pushing live progress. Also (re)connects the dash
  /// session if it isn't already up.
  Future<void> sendToDash() async {
    final destination = state.destination;
    final route = state.route;
    if (destination?.lat == null || destination?.lng == null) return;

    await DashEngine.instance.setDestination(
      name: destination!.name,
      lat: destination.lat,
      lng: destination.lng,
    );
    await DashEngine.instance.connect();

    _navLoop?.stop();
    if (route != null) {
      _navLoop = NavLoop(route)..start();
    }
  }

  void exitNavigation() {
    _navLoop?.stop();
    _navLoop = null;
    DashEngine.instance.clearDestination();
  }
}

final routeControllerProvider = NotifierProvider<RouteController, RouteState>(RouteController.new);

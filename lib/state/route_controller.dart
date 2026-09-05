import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:opendash_dash_engine/opendash_dash_engine.dart';

import '../models/shared_location.dart';
import '../nav/nav_loop.dart';
import '../nav/route.dart' as nav;
import '../util/app_logger.dart' show talker;

class RouteState {
  const RouteState({this.destination, this.route});

  final SharedLocation? destination;
  final nav.Route? route;

  RouteState copyWith({SharedLocation? destination, nav.Route? route}) => RouteState(
        destination: destination ?? this.destination,
        route: route ?? this.route,
      );
}

/// Holds the destination + route that were sent (or are about to be sent) to
/// the dash, and owns the [NavLoop] that pushes live progress while riding —
/// the Dart-side counterpart to the original `RouteViewModel.kt`.
///
/// **It does no route planning of its own.** Picking a destination and
/// fetching alternatives both happen on [RoutePreviewScreen], which calls
/// `Router.routes()` directly and reports its own errors; this controller only
/// adopts the finished result via [selectRoute]. That split is why there is no
/// error/loading state here — nothing in this class can fail before
/// [sendToDash], and `sendToDash`'s own failures surface as the dash session
/// not coming up (see `DashStage`), not as route-planning errors.
class RouteController extends Notifier<RouteState> {
  NavLoop? _navLoop;

  @override
  RouteState build() {
    ref.onDispose(() => _navLoop?.stop());
    return const RouteState();
  }

  /// Adopts a destination + route already resolved by the preview screen's
  /// alternative picker; [sendToDash] can be called right after.
  void selectRoute(SharedLocation destination, nav.Route route) {
    state = state.copyWith(destination: destination, route: route);
  }

  /// "Send to Dash": hands the destination + route to the native engine and
  /// starts the nav loop pushing live progress. Also (re)connects the dash
  /// session if it isn't already up.
  Future<void> sendToDash() async {
    final destination = state.destination;
    final route = state.route;
    if (destination == null) return;

    await DashEngine.instance.setDestination(
      name: destination.name,
      lat: destination.lat,
      lng: destination.lng,
    );
    await DashEngine.instance.connect();

    _navLoop?.stop();
    if (route != null) {
      _navLoop = NavLoop(route, onRerouted: (r) => state = state.copyWith(route: r))..start();
    }
  }

  void exitNavigation() {
    _navLoop?.stop();
    _navLoop = null;
    // A whole fresh state, not copyWith: destination and route are nullable
    // fields behind `??`, so copyWith cannot clear them — and leaving them set
    // meant the next visit to /home/dash drew the old polyline and destination
    // pin and offered "exit navigation" again, over a native side that had
    // already stopped navigating.
    state = const RouteState();
    // Fire-and-forget would make a channel failure an unhandled async error, on
    // a path the rider takes to *stop* — nothing here is worth surfacing, but
    // it does belong in the log.
    unawaited(
      DashEngine.instance.clearDestination().catchError(
        (Object e, StackTrace st) => talker.error('[Route] clearDestination failed', e, st),
      ),
    );
  }
}

final routeControllerProvider = NotifierProvider<RouteController, RouteState>(RouteController.new);

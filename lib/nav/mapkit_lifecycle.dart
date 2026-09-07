import 'package:yandex_maps_mapkit/mapkit_factory.dart' show mapkit;

import '../util/app_logger.dart';

/// Owns the single question "should MapKit be running right now?".
///
/// MapKit mirrors `Activity.onStart()/onStop()` and does nothing at all while
/// stopped — search and routing requests never leave the device, with no error
/// and no timeout: the `Completer` in [Router.routes] simply never completes.
/// That collided head-on with this app's headline scenario. Riding with the
/// phone's screen off is the *normal* mode (see CLAUDE.md), and a dark screen is
/// `onPause` + `onStop` on the activity, i.e. `AppLifecycleState.paused`. The
/// lifecycle observer stopped MapKit there, so every off-route reroute
/// (`NavLoop._reroute` → `Router.route`) hung for the rest of the ride —
/// spec/route_restructuring.md held only while the rider kept the screen on.
///
/// So the decision has two inputs, not one: MapKit runs while the app is in the
/// foreground **or** while a route is being navigated. The foreground service
/// keeps the process alive for the latter anyway.
class MapkitLifecycle {
  MapkitLifecycle._();

  static bool _foreground = true;
  static bool _navigating = false;
  static bool _running = false;

  /// Call once at startup, before the first [setForeground]/[setNavigating].
  static void start() {
    _running = true;
    mapkit.onStart();
  }

  static void setForeground(bool value) {
    if (_foreground == value) return;
    _foreground = value;
    _apply();
  }

  /// Set by [NavLoop] for as long as it is driving a route.
  static void setNavigating(bool value) {
    if (_navigating == value) return;
    _navigating = value;
    _apply();
  }

  static void _apply() {
    final wanted = _foreground || _navigating;
    if (wanted == _running) return;
    _running = wanted;
    talker.info('[MapKit] ${wanted ? 'onStart' : 'onStop'} '
        '(foreground=$_foreground navigating=$_navigating)');
    if (wanted) {
      mapkit.onStart();
    } else {
      mapkit.onStop();
    }
  }
}

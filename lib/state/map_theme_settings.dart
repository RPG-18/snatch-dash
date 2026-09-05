import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../util/app_logger.dart' show talker;

/// Palette the dash map is drawn with. Two, deliberately: an automatic
/// sunset/sunrise mode was dropped because switching themes reloads the whole
/// style, and doing that mid-ride is a visible break in the frame
/// (spec/drawing_from_local_tiles.md).
enum MapTheme {
  light('light'),
  dark('dark');

  const MapTheme(this.key);

  /// Value stored in preferences — **this string is a contract with Kotlin.**
  /// `MapStyleAssembler.theme()` reads the same key natively at stream start;
  /// renaming it here silently pins the dash to the light style.
  final String key;
}

/// Key under `FlutterSharedPreferences`. The native side looks for it prefixed
/// with `flutter.`, which `shared_preferences` adds on this side.
const kMapThemePrefsKey = 'map_theme';

/// Selected map theme. Light by default.
///
/// The engine does not learn about a change through a channel: it re-reads this
/// preference when a stream starts, so switching themes takes effect on the
/// next connect rather than interrupting a ride.
class MapThemeSettings extends Notifier<MapTheme> {
  /// Whether the rider has already chosen during this session. Guards against a
  /// slow [_load] landing after [select] and overwriting the fresh choice with
  /// the stored one — the tap would appear to revert by itself a moment later.
  bool _chosen = false;

  @override
  MapTheme build() {
    Future.microtask(_load);
    return MapTheme.light;
  }

  Future<void> _load() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final stored = prefs.getString(kMapThemePrefsKey);
      if (!ref.mounted || _chosen) return;
      state = MapTheme.values.firstWhere((t) => t.key == stored, orElse: () => MapTheme.light);
    } catch (e, st) {
      // Nothing awaits the microtask this runs in, so a failure here would
      // otherwise be an unhandled async error. The default (light) stands.
      talker.error('[MapTheme] could not read the stored theme', e, st);
    }
  }

  Future<void> select(MapTheme theme) async {
    _chosen = true;
    state = theme;
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(kMapThemePrefsKey, theme.key);
    } catch (e, st) {
      // The engine reads this preference natively at stream start, so a failed
      // write means the dash keeps the old palette while the interface shows the
      // new one. Worth a log line; not worth blocking the rider.
      talker.error('[MapTheme] could not store the theme', e, st);
    }
  }
}

final mapThemeSettingsProvider =
    NotifierProvider<MapThemeSettings, MapTheme>(MapThemeSettings.new);

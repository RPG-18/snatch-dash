import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

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
  @override
  MapTheme build() {
    Future.microtask(_load);
    return MapTheme.light;
  }

  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    final stored = prefs.getString(kMapThemePrefsKey);
    if (!ref.mounted) return;
    state = MapTheme.values.firstWhere((t) => t.key == stored, orElse: () => MapTheme.light);
  }

  Future<void> select(MapTheme theme) async {
    state = theme;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(kMapThemePrefsKey, theme.key);
  }
}

final mapThemeSettingsProvider =
    NotifierProvider<MapThemeSettings, MapTheme>(MapThemeSettings.new);

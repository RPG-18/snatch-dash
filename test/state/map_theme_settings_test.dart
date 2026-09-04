import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:snatch_dash/state/map_theme_settings.dart';

Future<void> _settle() => Future<void>.delayed(Duration.zero);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('defaults to light', () async {
    SharedPreferences.setMockInitialValues({});
    final container = ProviderContainer();
    addTearDown(container.dispose);

    expect(container.read(mapThemeSettingsProvider), MapTheme.light);
  });

  test('restores the stored choice', () async {
    SharedPreferences.setMockInitialValues({kMapThemePrefsKey: 'dark'});
    final container = ProviderContainer();
    addTearDown(container.dispose);

    container.read(mapThemeSettingsProvider);
    await _settle();

    expect(container.read(mapThemeSettingsProvider), MapTheme.dark);
  });

  test('an unknown stored value falls back rather than failing', () async {
    SharedPreferences.setMockInitialValues({kMapThemePrefsKey: 'solarized'});
    final container = ProviderContainer();
    addTearDown(container.dispose);

    container.read(mapThemeSettingsProvider);
    await _settle();

    expect(container.read(mapThemeSettingsProvider), MapTheme.light);
  });

  test('stores exactly the strings Kotlin reads back', () async {
    // `MapStyleAssembler.theme()` compares against "dark" natively; these two
    // sides only agree by convention, so pin the convention here.
    SharedPreferences.setMockInitialValues({});
    final container = ProviderContainer();
    addTearDown(container.dispose);

    await container.read(mapThemeSettingsProvider.notifier).select(MapTheme.dark);

    final prefs = await SharedPreferences.getInstance();
    expect(prefs.getString(kMapThemePrefsKey), 'dark');
    expect(MapTheme.light.key, 'light');
  });
}

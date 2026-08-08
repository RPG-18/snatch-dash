import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:talker_riverpod_logger/talker_riverpod_logger.dart';
import 'package:yandex_maps_mapkit/init.dart' as ymk;
import 'package:yandex_maps_mapkit/mapkit_factory.dart' show mapkit;

import 'l10n/app_localizations.dart';
import 'router/app_router.dart';
import 'state/dash_wallpaper_store.dart';
import 'theme/app_theme.dart';
import 'util/app_logger.dart';

// Set via `--dart-define-from-file=android/dart_defines.local.properties`
// (gitignored; see android/dart_defines.defaults.properties for the
// bring-your-own-key template). Kept separate from android/local.properties
// because Flutter's dart-define-from-file parser rejects dotted keys like
// sdk.dir/flutter.sdk. Same key backs the Suggest API in lib/nav/suggest_api.dart
// — Yandex now issues one API key shared across its map products.
const _yandexApiKey = String.fromEnvironment('YANDEX_API_KEY');

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await attachPersistentLog();
  await initializeDateFormatting();
  await ymk.initMapkit(apiKey: _yandexApiKey);
  // MapKit stays idle — search/routing requests never leave the device —
  // until onStart() is called, mirroring Activity.onStart()/onStop() on the
  // native Android SDK this wraps.
  mapkit.onStart();
  WidgetsBinding.instance.addObserver(_MapkitLifecycleObserver());
  attachNativeLogBridge();
  runApp(ProviderScope(
    // TODO: riverpod logging is off for now — it was flooding app_log.txt
    // with one entry per state change (e.g. every RouteSearchController
    // keystroke). Flip back to true once that's trimmed down.
    observers: [TalkerRiverpodObserver(talker: talker, settings: const TalkerRiverpodLoggerSettings(enabled: false))],
    child: const OpenDashApp(),
  ));
}

class _MapkitLifecycleObserver extends WidgetsBindingObserver {
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    switch (state) {
      case AppLifecycleState.resumed:
        mapkit.onStart();
      case AppLifecycleState.paused:
        mapkit.onStop();
      case AppLifecycleState.inactive:
      case AppLifecycleState.detached:
      case AppLifecycleState.hidden:
        break;
    }
  }
}

class OpenDashApp extends ConsumerWidget {
  const OpenDashApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Eagerly build dashWallpaperStoreProvider here — it's otherwise only
    // watched by the Settings screen's wallpaper gallery, so if a session
    // never visits Settings before connecting to the dash, the Notifier's
    // build() (which pushes the saved wallpaper to the native engine via
    // DashEngine.setWallpaper) never runs and the dash idle screen stays
    // blank/black despite wallpaper slots being configured.
    ref.watch(dashWallpaperStoreProvider);
    return MaterialApp.router(
      title: 'SnatchDash',
      debugShowCheckedModeBanner: false,
      theme: buildOpenDashTheme(),
      routerConfig: appRouter,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
    );
  }
}

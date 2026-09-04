import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:talker_riverpod_logger/talker_riverpod_logger.dart';
import 'package:yandex_maps_mapkit/init.dart' as ymk;
import 'package:yandex_maps_mapkit/mapkit_factory.dart' show mapkit;

import 'l10n/app_localizations.dart';
import 'nav/voice_manager.dart';
import 'router/app_router.dart';
import 'state/dash_button_controller.dart';
import 'state/dash_connection_alert_controller.dart';
import 'state/offline_maps_controller.dart';
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
  // Restores the saved voice mode. Without this the manager keeps its initial
  // VoiceMode.chime forever — nothing else calls load() — so FULL (spoken
  // turn-by-turn) and OFF were both unreachable no matter what was persisted.
  await VoiceManager.instance.load();
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
    // Eagerly build dashButtonControllerProvider here: it only does anything
    // via its build()-time ref.listen, so it has to be watched from
    // somewhere alive for the whole app, or physical dash button presses
    // (zoom, call answer/reject, media skip) go nowhere.
    ref.watch(dashButtonControllerProvider);
    // Same reasoning again for dashConnectionAlertControllerProvider: it only
    // does anything via its own ref.listen, and the whole point is catching a
    // dash-connection drop while the rider isn't looking at the (deliberately
    // off) screen — so it must be alive for the whole session, not just while
    // the Dash screen happens to be open.
    ref.watch(dashConnectionAlertControllerProvider);
    // And once more for offlineMapsControllerProvider: its build() is where the
    // startup reconcile lives — the step that turns a download the system
    // finished while the app was dead into an installed pack. Left lazy, that
    // only ran when the rider happened to open the offline-maps screen, so the
    // navigation gate on Home stayed shut over a map that was sitting on disk,
    // fully downloaded. That scenario is the reason the system downloader was
    // chosen at all.
    // `.notifier`, not the state: the notifier instance is stable for the life
    // of the provider, while the state is replaced on every poll tick — and
    // OfflineMapsState has no value equality, so watching it would rebuild the
    // whole router (every page on the stack) about 1.4 times a second for as
    // long as any download is running. All that is needed here is that the
    // provider exists.
    ref.watch(offlineMapsControllerProvider.notifier);
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

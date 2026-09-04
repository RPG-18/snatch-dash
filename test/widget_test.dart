// Smoke test: the app boots and lands on the Home tab of the bottom nav shell.
//
// Home reads `vehicleStoreProvider` (SharedPreferences), `dashEngineStateProvider`
// (the native plugin's EventChannel), and the garage/saved-location
// repositories — none of those platforms are available under `flutter test`,
// so all three are given a fake here: SharedPreferences via its test-mode
// in-memory store, the dash engine's method/event channels via a mock
// `TestDefaultBinaryMessengerBinding` handler, and the repositories via their
// plain in-memory implementations (real `sqflite` behavior is covered
// separately by `test/data/sqlite_garage_repository_test.dart` — exercising
// the actual FFI-backed database isn't necessary, or reliably fast, inside a
// full widget-tree smoke test).

import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:snatch_dash/data/garage_repository.dart';
import 'package:snatch_dash/data/installed_packs_repository.dart';
import 'package:snatch_dash/data/saved_location_repository.dart';
import 'package:snatch_dash/main.dart';
import 'package:snatch_dash/state/offline_maps_controller.dart';
import 'package:snatch_dash/state/garage_controller.dart';
import 'package:snatch_dash/state/saved_destinations_controller.dart';

const mapsChannel = MethodChannel('ru.snatchdash.app/maps');

/// Methods the offline-maps controller reached for during the boot, so the test
/// can tell "the startup reconcile ran" from "nothing instantiated it".
final mapsCalls = <String>[];

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});

    const methodChannel = MethodChannel('opendash_dash_engine');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
      if (call.method == 'getConfig') {
        return <String, dynamic>{'ssidPrefix': 'RE_', 'ssid': '', 'password': '', 'needsDiscovery': true};
      }
      return null;
    });

    // EventChannel.receiveBroadcastStream sends 'listen'/'cancel' method
    // calls on a MethodChannel sharing the event channel's name — not a raw
    // message handler.
    const eventChannel = MethodChannel('opendash_dash_engine/state');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(eventChannel, (call) async => null);

    // The offline-maps controller is now watched from OpenDashApp itself (its
    // build() carries the startup reconcile), so booting the app reaches the
    // downloader channel whether or not the maps screen is ever opened.
    mapsCalls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(mapsChannel, (call) async {
      mapsCalls.add(call.method);
      return switch (call.method) {
        'mapsDir' => Directory.systemTemp.createTempSync('maps_test').path,
        'hasRoomFor' => true,
        'progress' => <Map<String, Object?>>[],
        // A list of rows, matching the channel contract (`invokeListMethod`) —
        // a bare map here throws a cast error inside the bootstrap, which the
        // controller now catches, leaving the test green while the drift check
        // it is meant to cover never ran.
        'installedFiles' => <Map<String, Object?>>[],
        'reconcile' => <Map<String, Object?>>[],
        _ => null,
      };
    });
  });

  tearDown(() {
    const methodChannel = MethodChannel('opendash_dash_engine');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, null);
    const eventChannel = MethodChannel('opendash_dash_engine/state');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(eventChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(mapsChannel, null);
  });

  testWidgets('App boots to Home tab', (WidgetTester tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          garageRepositoryProvider.overrideWithValue(InMemoryGarageRepository()),
          savedLocationRepositoryProvider.overrideWithValue(InMemorySavedLocationRepository()),
          // Home gates navigation on the pack registry, so booting it now
          // touches sqlite — which widget tests have no binding for.
          installedPacksRepositoryProvider
              .overrideWithValue(InMemoryInstalledPacksRepository()),
        ],
        child: const OpenDashApp(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('SnatchDash'), findsOneWidget);
    expect(find.text('Home'), findsOneWidget);
  });

  testWidgets('the startup reconcile runs without opening the maps screen', (tester) async {
    // What the system downloader was chosen for: the app is killed mid-download,
    // DownloadManager finishes the pack, and the next launch must install it.
    // That work lives in offlineMapsControllerProvider.build(), and the provider
    // is lazy — before it was watched from OpenDashApp, nothing instantiated it
    // until the rider opened «Офлайн-карты» by hand, so the navigation gate on
    // Home stayed shut over a map that was already on disk.
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          garageRepositoryProvider.overrideWithValue(InMemoryGarageRepository()),
          savedLocationRepositoryProvider.overrideWithValue(InMemorySavedLocationRepository()),
          installedPacksRepositoryProvider
              .overrideWithValue(InMemoryInstalledPacksRepository()),
        ],
        child: const OpenDashApp(),
      ),
    );
    await tester.pumpAndSettle();

    expect(mapsCalls, contains('reconcile'));
  });
}

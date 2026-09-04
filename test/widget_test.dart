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
  });

  tearDown(() {
    const methodChannel = MethodChannel('opendash_dash_engine');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, null);
    const eventChannel = MethodChannel('opendash_dash_engine/state');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(eventChannel, null);
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
}

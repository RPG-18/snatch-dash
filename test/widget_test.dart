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
import 'package:opendash_dash_engine/src/messages.g.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:snatch_dash/data/garage_repository.dart';
import 'package:snatch_dash/data/installed_packs_repository.dart';
import 'package:snatch_dash/data/saved_location_repository.dart';
import 'package:snatch_dash/main.dart';
import 'package:snatch_dash/state/garage_controller.dart';
import 'package:snatch_dash/state/offline_maps_controller.dart';
import 'package:snatch_dash/state/saved_destinations_controller.dart';

const mapsChannel = MethodChannel('ru.snatchdash.app/maps');

/// Methods the offline-maps controller reached for during the boot, so the test
/// can tell "the startup reconcile ran" from "nothing instantiated it".
final mapsCalls = <String>[];

/// The engine's host methods this file answers for, and its three streams.
/// One list each, read by both `setUp` and `tearDown` — the two drifting apart
/// is how nine handlers came to leak between tests.
const _mockedHostMethods = [
  'getConfig',
  'batteryOptimisationStatus',
  'isNotificationAccessGranted',
  'getPlatformVersion',
  'connect',
  'disconnect',
  // Not because a boot calls it, but because a FAILING boot does: any Flutter
  // error inside these tests goes to `rideError`, and an unmocked Pigeon
  // channel answers `channel-error` where the pre-Pigeon mock answered null.
  // Today that is swallowed by the `catchError` in error_reporting.dart, so
  // the cost is a second exception thrown while reporting the first — which
  // is the noise that makes the real failure hard to find.
  'rideError',
];

const _mockedStreams = ['state', 'button', 'log'];

BasicMessageChannel<Object?> _hostChannel(String method) =>
    BasicMessageChannel<Object?>(
      'dev.flutter.pigeon.opendash_dash_engine.DashEngineApi.$method',
      DashEngineApi.pigeonChannelCodec,
    );

MethodChannel _streamChannel(String stream) => MethodChannel(
  'dev.flutter.pigeon.opendash_dash_engine.DashEngineEvents.$stream',
);

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});

    // The engine's whole surface is Pigeon-generated since 2026-09-30, so the
    // boot mocks have to speak its channels: a `BasicMessageChannel` per host
    // method, named `dev.flutter.pigeon.<package>.<api>.<method>`, replying
    // with a one-element list. The `MethodChannel('opendash_dash_engine')`
    // that stood here intercepted nothing any more — harmless only because no
    // widget test happens to open Settings, and a trap for the first one that
    // does, which would meet a `PlatformException` raised inside the widget.
    Object? pigeonReply(String method) => switch (method) {
      'getConfig' => DashConfigView(
        ssidPrefix: 'RE_',
        ssid: '',
        password: '',
        needsDiscovery: true,
      ),
      'batteryOptimisationStatus' => BatteryOptimisationStatus(
        ignoring: false,
        canAsk: false,
        emuiWorkaroundNeeded: false,
      ),
      'isNotificationAccessGranted' => false,
      'getPlatformVersion' => 'test',
      _ => null,
    };
    for (final method in _mockedHostMethods) {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockDecodedMessageHandler<Object?>(
            _hostChannel(method),
            (Object? message) async => <Object?>[pigeonReply(method)],
          );
    }

    // EventChannel.receiveBroadcastStream sends 'listen'/'cancel' method
    // calls on a MethodChannel sharing the event channel's name — not a raw
    // message handler. One per generated stream.
    for (final stream in _mockedStreams) {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(_streamChannel(stream), (call) async => null);
    }

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
    // Exactly what setUp installed, from the same two lists. It used to clear
    // the two pre-Pigeon channels by name, which after the port cleared nothing
    // at all while nine handlers stayed registered between tests — hidden only
    // because setUp overwrites them on the way in.
    for (final method in _mockedHostMethods) {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockDecodedMessageHandler<Object?>(_hostChannel(method), null);
    }
    for (final stream in _mockedStreams) {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(_streamChannel(stream), null);
    }
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(mapsChannel, null);
  });

  testWidgets('App boots to Home tab', (WidgetTester tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          garageRepositoryProvider.overrideWithValue(
            InMemoryGarageRepository(),
          ),
          savedLocationRepositoryProvider.overrideWithValue(
            InMemorySavedLocationRepository(),
          ),
          // Home gates navigation on the pack registry, so booting it now
          // touches sqlite — which widget tests have no binding for.
          installedPacksRepositoryProvider.overrideWithValue(
            InMemoryInstalledPacksRepository(),
          ),
        ],
        child: const OpenDashApp(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('SnatchDash'), findsOneWidget);
    expect(find.text('Home'), findsOneWidget);
  });

  testWidgets('the startup reconcile runs without opening the maps screen', (
    tester,
  ) async {
    // What the system downloader was chosen for: the app is killed mid-download,
    // DownloadManager finishes the pack, and the next launch must install it.
    // That work lives in offlineMapsControllerProvider.build(), and the provider
    // is lazy — before it was watched from OpenDashApp, nothing instantiated it
    // until the rider opened «Офлайн-карты» by hand, so the navigation gate on
    // Home stayed shut over a map that was already on disk.
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          garageRepositoryProvider.overrideWithValue(
            InMemoryGarageRepository(),
          ),
          savedLocationRepositoryProvider.overrideWithValue(
            InMemorySavedLocationRepository(),
          ),
          installedPacksRepositoryProvider.overrideWithValue(
            InMemoryInstalledPacksRepository(),
          ),
        ],
        child: const OpenDashApp(),
      ),
    );
    await tester.pumpAndSettle();

    expect(mapsCalls, contains('reconcile'));
  });
}

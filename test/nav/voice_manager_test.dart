import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opendash_dash_engine/opendash_dash_engine.dart';
import 'package:opendash_dash_engine/opendash_dash_engine_platform_interface.dart';
import 'package:snatch_dash/nav/geo_point.dart';
import 'package:snatch_dash/nav/route.dart';
import 'package:snatch_dash/nav/voice_manager.dart';
import 'package:snatch_dash/nav/voice_settings.dart';

import '../voice_test_support.dart';

void main() {
  final binding = TestWidgetsFlutterBinding.ensureInitialized();
  late OpendashDashEnginePlatform originalPlatform;
  late FakeVoiceEngine engine;
  late VoicePreferencesDisk disk;
  late VoiceManager voice;

  setUp(() async {
    originalPlatform = OpendashDashEnginePlatform.instance;
    engine = FakeVoiceEngine();
    OpendashDashEnginePlatform.instance = engine;
    disk = VoicePreferencesDisk(binding)..install();
    voice = VoiceManager();
    await voice.initialize();
  });

  tearDown(() {
    binding.platformDispatcher.clearLocaleTestValue();
    OpendashDashEnginePlatform.instance = originalPlatform;
    disk.dispose();
  });

  Future<String> announce(ManeuverType type, {int? exitNumber}) async {
    engine.requests.clear();
    voice.resetTrip();
    await voice.maybeAnnounce(
      Maneuver(
        type: type,
        roadName: 'Some English street',
        location: const GeoPoint(0, 0),
        cumulativeMeters: 1000,
        roundaboutExitNumber: exitNumber,
      ),
      50,
      5000,
    );
    return engine.requests.single.text;
  }

  test(
    'Russian guidance stays Russian with English route annotations',
    () async {
      binding.platformDispatcher.localeTestValue = const Locale('ru');

      for (final type in ManeuverType.values) {
        final text = await announce(type);
        expect(text, matches(RegExp('[А-Яа-яЁё]')), reason: type.name);
        expect(text, isNot(matches(RegExp('[A-Za-z]'))), reason: type.name);
        expect(engine.requests.single.languageTag, 'ru-RU');
      }
    },
  );

  const directions = [
    (ManeuverType.slightLeft, ManeuverType.slightRight),
    (ManeuverType.left, ManeuverType.right),
    (ManeuverType.hardLeft, ManeuverType.hardRight),
    (ManeuverType.forkLeft, ManeuverType.forkRight),
    (ManeuverType.uturnLeft, ManeuverType.uturnRight),
    (ManeuverType.exitLeft, ManeuverType.exitRight),
  ];

  for (final language in ['ru', 'en']) {
    test('$language guidance preserves left and right directions', () async {
      binding.platformDispatcher.localeTestValue = Locale(language);
      final left = RegExp(language == 'ru' ? 'лев' : r'\bleft\b');
      final right = RegExp(language == 'ru' ? 'прав' : r'\bright\b');

      for (final pair in directions) {
        final leftText = await announce(pair.$1);
        expect(leftText, matches(left), reason: pair.$1.name);
        expect(leftText, isNot(matches(right)), reason: pair.$1.name);
        final rightText = await announce(pair.$2);
        expect(rightText, matches(right), reason: pair.$2.name);
        expect(rightText, isNot(matches(left)), reason: pair.$2.name);
        expect(
          engine.requests.single.languageTag,
          language == 'ru' ? 'ru-RU' : 'en-US',
        );
      }
    });

    test('$language guidance includes the supplied roundabout exit', () async {
      binding.platformDispatcher.localeTestValue = Locale(language);

      for (final exitNumber in [1, 2, 5, 21]) {
        final text = await announce(
          ManeuverType.leaveRoundabout,
          exitNumber: exitNumber,
        );
        expect(text, matches(RegExp('\\b$exitNumber\\b')));
        expect(text, isNot(contains('Some English street')));
      }
      final withoutNumber = await announce(ManeuverType.leaveRoundabout);
      expect(withoutNumber, isNot(matches(RegExp(r'\d'))));
      expect(
        withoutNumber,
        matches(RegExp(language == 'ru' ? 'круг' : 'roundabout')),
      );
    });
  }

  const turn = Maneuver(
    type: ManeuverType.right,
    location: GeoPoint(0, 0),
    cumulativeMeters: 1000,
  );

  test(
    'all announcements stay silent until initialization completes',
    () async {
      final reading = Completer<void>();
      final releaseRead = Completer<void>();
      disk.beforeRead = () {
        reading.complete();
        return releaseRead.future;
      };
      // A fresh preferences cache models a new application startup.
      disk.install();
      voice = VoiceManager();
      await voice.maybeAnnounce(turn, 250, 5000);
      await voice.announceConnectionLost();
      await voice.announceConnectionRestored();
      expect(engine.requests, isEmpty);

      final first = voice.initialize();
      final second = voice.initialize();
      await reading.future;
      await voice.maybeAnnounce(turn, 250, 5000);
      await voice.announceConnectionLost();
      await voice.announceConnectionRestored();
      expect(engine.requests, isEmpty);

      releaseRead.complete();
      await Future.wait([first, second]);
      expect(disk.reads, 2); // One setUp read, one shared startup read.
      await voice.maybeAnnounce(turn, 250, 5000);
      await voice.announceConnectionLost();
      await voice.announceConnectionRestored();
      expect(engine.requests, hasLength(3));
    },
  );

  test(
    'off stops pending speech before disk and does not replay its cue',
    () async {
      final speech = Completer<VoiceSpeechResult>();
      engine.onSpeak = () => speech.future;
      engine.onStop = () => speech.complete(VoiceSpeechResult.cancelled);
      final speaking = voice.maybeAnnounce(turn, 250, 5000);
      expect(engine.requests, hasLength(1));
      final writeStarted = Completer<void>();
      final releaseWrite = Completer<void>();
      disk.beforeWrite = (key, value) async {
        if (!writeStarted.isCompleted) {
          writeStarted.complete();
          await releaseWrite.future;
        }
        return true;
      };

      final saving = voice.applySettings(
        voice.settings.copyWith(enabled: false),
      );
      expect(voice.settings.enabled, isFalse);
      expect(engine.stops, 1);
      expect(disk.values['voice_enabled'], isNull);
      await speaking;
      await writeStarted.future;
      await voice.maybeAnnounce(turn, 250, 5000);
      await voice.announceConnectionLost();
      await voice.announceConnectionRestored();
      expect(engine.requests, hasLength(1));

      releaseWrite.complete();
      await saving;
      engine.onSpeak = null;
      engine.onStop = null;
      await voice.applySettings(voice.settings.copyWith(enabled: true));
      await voice.maybeAnnounce(turn, 250, 5000);
      expect(engine.requests, hasLength(1));
      await voice.maybeAnnounce(turn, 140, 5000);
      expect(engine.requests, hasLength(2));
    },
  );

  test(
    'volume and policy affect the next phrase without restarting speech',
    () async {
      final speech = Completer<VoiceSpeechResult>();
      engine.onSpeak = () => speech.future;
      final speaking = voice.maybeAnnounce(turn, 250, 5000);
      final first = engine.requests.single;

      await voice.applySettings(
        voice.settings.copyWith(
          volume: VoiceVolume.high,
          musicPolicy: VoiceMusicPolicy.pauseAndResume,
        ),
      );
      expect(engine.stops, 0);
      expect(engine.requests, hasLength(1));
      expect(first.volume, 0.5);
      expect(first.policy, VoiceMusicPolicy.duck);

      speech.complete(VoiceSpeechResult.completed);
      await speaking;
      engine.onSpeak = null;
      await voice.maybeAnnounce(turn, 140, 5000);
      expect(engine.requests.last.volume, 0.75);
      expect(engine.requests.last.policy, VoiceMusicPolicy.pauseAndResume);
    },
  );

  for (final outcome in ['cancelled', 'focusDenied', 'native error']) {
    test(
      '$outcome consumes a cue without breaking the next announcement',
      () async {
        engine.onSpeak = () => outcome == 'native error'
            ? Future.error(PlatformException(code: 'VOICE_TTS_PLAYBACK'))
            : Future.value(
                outcome == 'cancelled'
                    ? VoiceSpeechResult.cancelled
                    : VoiceSpeechResult.focusDenied,
              );
        await voice.maybeAnnounce(turn, 250, 5000);
        await voice.maybeAnnounce(turn, 250, 5000);
        expect(engine.requests, hasLength(1));

        engine.onSpeak = null;
        await voice.maybeAnnounce(turn, 140, 5000);
        await voice.announceConnectionLost();
        await voice.announceConnectionRestored();
        expect(engine.requests, hasLength(4));
      },
    );
  }
}

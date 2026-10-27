import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opendash_dash_engine/opendash_dash_engine.dart';
import 'package:opendash_dash_engine/opendash_dash_engine_platform_interface.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:snatch_dash/nav/geo_point.dart';
import 'package:snatch_dash/nav/route.dart';
import 'package:snatch_dash/nav/voice_manager.dart';
import 'package:snatch_dash/nav/voice_settings.dart';
import 'package:snatch_dash/state/voice_settings.dart';

import '../voice_test_support.dart';

void main() {
  final binding = TestWidgetsFlutterBinding.ensureInitialized();
  late OpendashDashEnginePlatform originalPlatform;
  late FakeVoiceEngine engine;
  late VoicePreferencesDisk disk;

  setUp(() {
    originalPlatform = OpendashDashEnginePlatform.instance;
    engine = FakeVoiceEngine();
    OpendashDashEnginePlatform.instance = engine;
    disk = VoicePreferencesDisk(binding)..install();
  });

  tearDown(() {
    OpendashDashEnginePlatform.instance = originalPlatform;
    disk.dispose();
  });

  Future<VoiceManager> restart() async {
    SharedPreferences.resetStatic();
    final voice = VoiceManager();
    await voice.initialize();
    return voice;
  }

  void expectSettings(VoiceSettings actual, VoiceSettings expected) {
    expect(actual.enabled, expected.enabled);
    expect(actual.volume, expected.volume);
    expect(actual.musicPolicy, expected.musicPolicy);
  }

  const turn = Maneuver(
    type: ManeuverType.right,
    location: GeoPoint(0, 0),
    cumulativeMeters: 1000,
  );

  test(
    'persisted off mutes all sources; enabling uses saved volume and policy',
    () async {
      final voice = await restart();
      await voice.applySettings(
        const VoiceSettings(
          enabled: false,
          volume: VoiceVolume.low,
          musicPolicy: VoiceMusicPolicy.unchanged,
        ),
      );
      final restored = await restart();
      await restored.maybeAnnounce(turn, 250, 5000);
      await restored.announceConnectionLost();
      await restored.announceConnectionRestored();
      expect(engine.requests, isEmpty);

      await restored.applySettings(restored.settings.copyWith(enabled: true));
      // Muted ticks must not consume the scheduler's pending cue.
      await restored.maybeAnnounce(turn, 250, 5000);
      await restored.announceConnectionLost();
      await restored.announceConnectionRestored();
      expect(engine.requests, hasLength(3));
      for (final request in engine.requests) {
        expect(request.volume, 0.25);
        expect(request.policy, VoiceMusicPolicy.unchanged);
      }
    },
  );

  final migrations =
      <({String name, Map<String, Object> stored, VoiceSettings expected})>[
        (
          name: 'legacy off',
          stored: {'voice_mode': 'off'},
          expected: const VoiceSettings(enabled: false),
        ),
        (
          name: 'legacy chime becomes enabled speech',
          stored: {'voice_mode': 'chime'},
          expected: const VoiceSettings(),
        ),
        (
          name: 'legacy full',
          stored: {'voice_mode': 'full'},
          expected: const VoiceSettings(),
        ),
        (
          name: 'unknown legacy',
          stored: {'voice_mode': 'quiet'},
          expected: const VoiceSettings(),
        ),
        (
          name: 'wrong legacy type',
          stored: {'voice_mode': 1},
          expected: const VoiceSettings(),
        ),
        (
          name: 'new enabled wins over legacy off',
          stored: {
            'voice_enabled': true,
            'voice_volume': 'high',
            'voice_music_policy': 'pauseAndResume',
            'voice_mode': 'off',
          },
          expected: const VoiceSettings(
            volume: VoiceVolume.high,
            musicPolicy: VoiceMusicPolicy.pauseAndResume,
          ),
        ),
        (
          name: 'new off wins over legacy full',
          stored: {'voice_enabled': false, 'voice_mode': 'full'},
          expected: const VoiceSettings(enabled: false),
        ),
        (
          name:
              'wrong enabled type falls back to legacy without losing valid neighbors',
          stored: {
            'voice_enabled': 'true',
            'voice_volume': 'low',
            'voice_music_policy': 'unchanged',
            'voice_mode': 'off',
          },
          expected: const VoiceSettings(
            enabled: false,
            volume: VoiceVolume.low,
            musicPolicy: VoiceMusicPolicy.unchanged,
          ),
        ),
      ];

  for (final scenario in migrations) {
    test('migration: ${scenario.name}', () async {
      disk.values.addAll(scenario.stored);
      final voice = await restart();
      expectSettings(voice.settings, scenario.expected);
      expect(disk.values.containsKey('voice_mode'), isFalse);
      expect(disk.values['voice_enabled'], scenario.expected.enabled);
      expect(disk.values['voice_volume'], scenario.expected.volume.name);
      expect(
        disk.values['voice_music_policy'],
        scenario.expected.musicPolicy.name,
      );
      expectSettings((await restart()).settings, scenario.expected);
    });
  }

  final malformed =
      <({String name, Map<String, Object> stored, VoiceSettings expected})>[
        (
          name: 'invalid enabled preserves volume and policy',
          stored: {
            'voice_enabled': 0,
            'voice_volume': 'low',
            'voice_music_policy': 'unchanged',
          },
          expected: const VoiceSettings(
            volume: VoiceVolume.low,
            musicPolicy: VoiceMusicPolicy.unchanged,
          ),
        ),
        (
          name: 'unknown volume preserves disabled and policy',
          stored: {
            'voice_enabled': false,
            'voice_volume': 'loud',
            'voice_music_policy': 'pauseAndResume',
          },
          expected: const VoiceSettings(
            enabled: false,
            musicPolicy: VoiceMusicPolicy.pauseAndResume,
          ),
        ),
        (
          name: 'wrong volume type preserves enabled and policy',
          stored: {'voice_volume': 25, 'voice_music_policy': 'unchanged'},
          expected: const VoiceSettings(
            musicPolicy: VoiceMusicPolicy.unchanged,
          ),
        ),
        (
          name: 'unknown policy preserves disabled and volume',
          stored: {
            'voice_enabled': false,
            'voice_volume': 'high',
            'voice_music_policy': 'mute',
          },
          expected: const VoiceSettings(
            enabled: false,
            volume: VoiceVolume.high,
          ),
        ),
        (
          name: 'wrong policy type preserves volume',
          stored: {'voice_volume': 'low', 'voice_music_policy': false},
          expected: const VoiceSettings(volume: VoiceVolume.low),
        ),
        (
          name: 'missing fields preserve the existing volume',
          stored: {'voice_volume': 'high'},
          expected: const VoiceSettings(volume: VoiceVolume.high),
        ),
      ];

  for (final scenario in malformed) {
    test('field recovery: ${scenario.name}', () async {
      disk.values.addAll(scenario.stored);
      final voice = await restart();
      expectSettings(voice.settings, scenario.expected);
      expect(disk.writes, isEmpty);
      expect(disk.values, scenario.stored);
    });
  }

  for (final throwsError in [false, true]) {
    final failure = throwsError ? 'exception' : 'false result';
    test(
      'migration retains legacy after write $failure and retries next launch',
      () async {
        disk.values['voice_mode'] = 'off';
        disk.beforeWrite = (key, value) async {
          if (key != 'voice_volume') return true;
          if (throwsError) throw PlatformException(code: 'DISK_WRITE');
          return false;
        };
        final voice = await restart();
        expect(voice.settings.enabled, isFalse);
        expect(disk.values['voice_mode'], 'off');
        expect(disk.removals, isEmpty);
        await voice.announceConnectionLost();
        expect(engine.requests, isEmpty);

        disk.beforeWrite = null;
        final restored = await restart();
        expect(restored.settings.enabled, isFalse);
        expect(disk.values.containsKey('voice_mode'), isFalse);
        expect(disk.values['voice_volume'], 'medium');
        expect(disk.values['voice_music_policy'], 'duck');
      },
    );

    test(
      'save $failure keeps runtime choice and does not poison queued writes',
      () async {
        final voice = await restart();
        var failed = false;
        final writeStarted = Completer<void>();
        final releaseFailure = Completer<void>();
        disk.beforeWrite = (key, value) async {
          if (failed) return true;
          failed = true;
          writeStarted.complete();
          await releaseFailure.future;
          if (throwsError) throw PlatformException(code: 'DISK_WRITE');
          return false;
        };
        const firstChoice = VoiceSettings(
          volume: VoiceVolume.low,
          musicPolicy: VoiceMusicPolicy.unchanged,
        );
        final firstSave = voice.applySettings(firstChoice);
        expectSettings(voice.settings, firstChoice);
        await voice.announceConnectionLost();
        expect(engine.requests.single.volume, 0.25);
        expect(engine.requests.single.policy, VoiceMusicPolicy.unchanged);
        await writeStarted.future;

        const finalChoice = VoiceSettings(
          enabled: false,
          volume: VoiceVolume.high,
          musicPolicy: VoiceMusicPolicy.pauseAndResume,
        );
        final finalSave = voice.applySettings(finalChoice);
        releaseFailure.complete();
        await Future.wait([firstSave, finalSave]);
        expectSettings((await restart()).settings, finalChoice);
      },
    );
  }

  test(
    'legacy is removed only after all migration fields reach disk',
    () async {
      disk.values['voice_mode'] = 'chime';
      final writeStarted = Completer<void>();
      final releaseWrite = Completer<void>();
      disk.beforeWrite = (key, value) async {
        if (key == 'voice_music_policy') {
          writeStarted.complete();
          await releaseWrite.future;
        }
        return true;
      };
      final voice = VoiceManager();
      final loading = voice.initialize();
      await writeStarted.future;
      expect(disk.values['voice_mode'], 'chime');
      expect(disk.removals, isEmpty);
      releaseWrite.complete();
      await loading;
      expect(disk.values.containsKey('voice_mode'), isFalse);
      expect(disk.values['voice_enabled'], isTrue);
      expect(disk.values['voice_volume'], 'medium');
      expect(disk.values['voice_music_policy'], 'duck');
    },
  );

  test(
    'read failure allows initialized guidance without writing fallback values',
    () async {
      disk.beforeRead = () async => throw PlatformException(code: 'DISK_READ');
      final voice = await restart();
      await voice.announceConnectionLost();
      expect(engine.requests, hasLength(1));
      expect(engine.requests.single.volume, 0.5);
      expect(engine.requests.single.policy, VoiceMusicPolicy.duck);
      expect(disk.writes, isEmpty);
    },
  );

  test(
    'provider changes apply immediately and serialize delayed snapshots',
    () async {
      disk.values.addAll({
        'voice_enabled': false,
        'voice_volume': 'low',
        'voice_music_policy': 'unchanged',
      });
      final voice = VoiceManager.instance;
      await voice.initialize();
      final container = ProviderContainer();
      addTearDown(container.dispose);
      final controller = container.read(voiceSettingsProvider.notifier);
      expectSettings(
        container.read(voiceSettingsProvider),
        const VoiceSettings(
          enabled: false,
          volume: VoiceVolume.low,
          musicPolicy: VoiceMusicPolicy.unchanged,
        ),
      );
      final writeStarted = Completer<void>();
      final releaseWrite = Completer<void>();
      disk.beforeWrite = (key, value) async {
        if (!writeStarted.isCompleted) {
          writeStarted.complete();
          await releaseWrite.future;
        }
        return true;
      };

      final enabled = controller.setEnabled(true);
      expect(container.read(voiceSettingsProvider).enabled, isTrue);
      await writeStarted.future;
      final volume = controller.setVolume(VoiceVolume.high);
      expect(container.read(voiceSettingsProvider).volume, VoiceVolume.high);
      final policy = controller.setMusicPolicy(VoiceMusicPolicy.pauseAndResume);
      expect(
        container.read(voiceSettingsProvider).musicPolicy,
        VoiceMusicPolicy.pauseAndResume,
      );
      await voice.announceConnectionRestored();
      expect(engine.requests.single.volume, 0.75);
      expect(engine.requests.single.policy, VoiceMusicPolicy.pauseAndResume);
      expect(disk.values['voice_enabled'], isFalse);
      expect(disk.values['voice_volume'], 'low');

      releaseWrite.complete();
      await Future.wait([enabled, volume, policy]);
      final restored = await restart();
      expectSettings(
        restored.settings,
        const VoiceSettings(
          volume: VoiceVolume.high,
          musicPolicy: VoiceMusicPolicy.pauseAndResume,
        ),
      );
      await restored.announceConnectionLost();
      expect(engine.requests, hasLength(2));
      expect(engine.requests.last.volume, 0.75);
      expect(engine.requests.last.policy, VoiceMusicPolicy.pauseAndResume);
    },
  );
}

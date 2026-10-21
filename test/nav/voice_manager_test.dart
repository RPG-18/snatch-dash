import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:snatch_dash/nav/geo_point.dart';
import 'package:snatch_dash/nav/route.dart';
import 'package:snatch_dash/nav/voice_manager.dart';

void main() {
  final binding = TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('flutter_tts');
  final spoken = <String>[];
  final languages = <String>[];
  late VoiceManager voice;

  setUp(() async {
    spoken.clear();
    languages.clear();
    SharedPreferences.setMockInitialValues({});
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      if (call.method == 'setLanguage') {
        languages.add(call.arguments as String);
      } else if (call.method == 'speak') {
        final arguments = call.arguments;
        spoken.add(
          arguments is String
              ? arguments
              : (arguments as Map<Object?, Object?>)['text']! as String,
        );
      }
      return 1;
    });
    voice = VoiceManager.instance;
    voice.resetTrip();
    await voice.setMode(VoiceMode.full);
  });

  tearDown(() async {
    voice.resetTrip();
    await voice.setMode(VoiceMode.off);
    binding.platformDispatcher.clearLocaleTestValue();
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
  });

  Future<String> announce(ManeuverType type, {int? exitNumber}) async {
    spoken.clear();
    languages.clear();
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
    return spoken.single;
  }

  test(
    'Russian guidance stays Russian with English route annotations',
    () async {
      binding.platformDispatcher.localeTestValue = const Locale('ru');

      for (final type in ManeuverType.values) {
        final text = await announce(type);
        expect(text, matches(RegExp('[А-Яа-яЁё]')), reason: type.name);
        expect(text, isNot(matches(RegExp('[A-Za-z]'))), reason: type.name);
        expect(languages.single, 'ru-RU');
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
        expect(languages.single, language == 'ru' ? 'ru-RU' : 'en-US');
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
}

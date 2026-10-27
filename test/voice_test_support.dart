import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opendash_dash_engine/opendash_dash_engine.dart';
import 'package:opendash_dash_engine/opendash_dash_engine_platform_interface.dart';
import 'package:shared_preferences/shared_preferences.dart';

class VoiceRequest {
  const VoiceRequest(this.text, this.languageTag, this.volume, this.policy);

  final String text;
  final String languageTag;
  final double volume;
  final VoiceMusicPolicy policy;
}

class FakeVoiceEngine extends OpendashDashEnginePlatform {
  final requests = <VoiceRequest>[];
  int stops = 0;
  Future<VoiceSpeechResult> Function()? onSpeak;
  void Function()? onStop;

  @override
  Future<VoiceSpeechResult> speakVoice({
    required String text,
    required String languageTag,
    required double volume,
    required VoiceMusicPolicy policy,
  }) {
    requests.add(VoiceRequest(text, languageTag, volume, policy));
    return onSpeak?.call() ?? Future.value(VoiceSpeechResult.completed);
  }

  @override
  Future<void> stopVoice() async {
    stops++;
    onStop?.call();
  }
}

// Disk changes only after a successful platform write. SharedPreferences' own
// eager cache must not make failed or unfinished writes appear persistent.
class VoicePreferencesDisk {
  VoicePreferencesDisk(this.binding);

  static const _channel = MethodChannel(
    'plugins.flutter.io/shared_preferences',
  );
  final TestWidgetsFlutterBinding binding;
  final values = <String, Object>{};
  final writes = <String>[];
  final removals = <String>[];
  int reads = 0;
  Future<void> Function()? beforeRead;
  Future<bool> Function(String key, Object value)? beforeWrite;

  void install() {
    SharedPreferences.resetStatic();
    binding.defaultBinaryMessenger.setMockMethodCallHandler(_channel, _handle);
  }

  void dispose() {
    SharedPreferences.resetStatic();
    binding.defaultBinaryMessenger.setMockMethodCallHandler(_channel, null);
  }

  Future<Object?> _handle(MethodCall call) async {
    if (call.method == 'getAll') {
      reads++;
      await beforeRead?.call();
      return values.map((key, value) => MapEntry('flutter.$key', value));
    }
    final arguments = call.arguments as Map<Object?, Object?>;
    final key = (arguments['key']! as String).substring('flutter.'.length);
    if (call.method == 'remove') {
      removals.add(key);
      values.remove(key);
      return true;
    }
    if (call.method == 'setBool' || call.method == 'setString') {
      final value = arguments['value']!;
      writes.add(key);
      if (!(await beforeWrite?.call(key, value) ?? true)) return false;
      values[key] = value;
      return true;
    }
    throw StateError('Unexpected preferences operation: ${call.method}');
  }
}

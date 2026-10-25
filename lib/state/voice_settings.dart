import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:opendash_dash_engine/opendash_dash_engine.dart';

import '../nav/voice_manager.dart';
import '../nav/voice_settings.dart';

class VoiceSettingsController extends Notifier<VoiceSettings> {
  @override
  VoiceSettings build() => VoiceManager.instance.settings;

  Future<void> setEnabled(bool enabled) {
    state = state.copyWith(enabled: enabled);
    return VoiceManager.instance.applySettings(state);
  }

  Future<void> setVolume(VoiceVolume volume) {
    state = state.copyWith(volume: volume);
    return VoiceManager.instance.applySettings(state);
  }

  Future<void> setMusicPolicy(VoiceMusicPolicy policy) {
    state = state.copyWith(musicPolicy: policy);
    return VoiceManager.instance.applySettings(state);
  }
}

final voiceSettingsProvider =
    NotifierProvider<VoiceSettingsController, VoiceSettings>(
      VoiceSettingsController.new,
    );

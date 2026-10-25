import 'package:opendash_dash_engine/opendash_dash_engine.dart';

enum VoiceVolume {
  low,
  medium,
  high;

  double get gain => switch (this) {
    VoiceVolume.low => 0.25,
    VoiceVolume.medium => 0.50,
    VoiceVolume.high => 0.75,
  };
}

class VoiceSettings {
  const VoiceSettings({
    this.enabled = true,
    this.volume = VoiceVolume.medium,
    this.musicPolicy = VoiceMusicPolicy.duck,
  });

  final bool enabled;
  final VoiceVolume volume;
  final VoiceMusicPolicy musicPolicy;

  VoiceSettings copyWith({
    bool? enabled,
    VoiceVolume? volume,
    VoiceMusicPolicy? musicPolicy,
  }) => VoiceSettings(
    enabled: enabled ?? this.enabled,
    volume: volume ?? this.volume,
    musicPolicy: musicPolicy ?? this.musicPolicy,
  );
}

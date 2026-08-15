import 'package:flutter_riverpod/legacy.dart';
import 'package:flutter_tts/flutter_tts.dart';
import 'package:opendash_dash_engine/opendash_dash_engine.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../l10n/app_localizations.dart';
import '../util/device_localizations.dart';
import 'route.dart';

enum VoiceMode { off, chime, full }

const _prefsKeyMode = 'voice_mode';

/// Turn-by-turn voice guidance. Ported from `dash/nav/VoiceManager.kt` — same
/// far/near de-duped announcement scheduling. FULL speaks via `flutter_tts`;
/// CHIME beeps through the native plugin's `playChime()` (no Dart/Flutter
/// equivalent to `ToneGenerator`, see the plugin's `DashEngineController`).
///
/// Note: the original's `turnPhrase` covered every `ManeuverType` from OSRM's
/// step-by-step instructions. `nav/Route.dart`'s `ManeuverType` now mirrors
/// the Yandex driving router's real per-step turns too, but this file's
/// phrasing hasn't caught up yet — [_turnPhrase] collapses every non-arrive
/// type to the generic "continue" phrase pending real l10n strings per turn
/// direction. Not a regression versus the original: the dash glyph is still
/// hardcoded to CONTINUE for every turn (unverified glyph codes), so no
/// turn-by-turn fidelity is lost on the dash side either.
class VoiceManager {
  VoiceManager._();
  static final VoiceManager instance = VoiceManager._();

  static const _farM = 450.0;
  static const _nearM = 60.0;
  static const _arriveM = 30.0;

  final _tts = FlutterTts();
  VoiceMode _mode = VoiceMode.chime;
  bool _loaded = false;

  double _lastManeuverKey = -1.0;
  bool _farDone = false;
  bool _nearDone = false;
  bool _arrived = false;

  VoiceMode get mode => _mode;

  Future<void> load() async {
    if (_loaded) return;
    _loaded = true;
    final prefs = await SharedPreferences.getInstance();
    final saved = prefs.getString(_prefsKeyMode);
    _mode = VoiceMode.values.firstWhere(
      (m) => m.name == saved,
      orElse: () => VoiceMode.chime,
    );
  }

  Future<void> setMode(VoiceMode mode) async {
    _mode = mode;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_prefsKeyMode, mode.name);
    if (mode == VoiceMode.off) {
      await _tts.stop();
    }
  }

  /// Call every nav tick. [maneuver] is the upcoming turn (null = none),
  /// [distanceM] the distance to it, [remainingM] distance to the destination.
  Future<void> maybeAnnounce(Maneuver? maneuver, double distanceM, double remainingM) async {
    if (_mode == VoiceMode.off) return;
    final l10n = deviceLocalizations();

    if (remainingM >= 0 && remainingM <= _arriveM && !_arrived) {
      _arrived = true;
      if (_mode == VoiceMode.full) {
        await _speak(l10n.voiceArrived);
      } else {
        await DashEngine.instance.playChime();
      }
      return;
    }
    if (remainingM > _arriveM) _arrived = false;

    if (maneuver == null) return;
    if (maneuver.cumulativeMeters != _lastManeuverKey) {
      _lastManeuverKey = maneuver.cumulativeMeters;
      _farDone = false;
      _nearDone = false;
    }

    if (distanceM >= 0 && distanceM <= _nearM && !_nearDone) {
      _nearDone = true;
      _farDone = true;
      if (_mode == VoiceMode.full) {
        await _speak(l10n.voiceNow(_turnPhrase(l10n, maneuver)));
      } else {
        await DashEngine.instance.playChime();
      }
    } else if (distanceM > _nearM && distanceM <= _farM && !_farDone) {
      _farDone = true;
      if (_mode == VoiceMode.full) {
        await _speak(l10n.voiceIn(_roundDist(l10n, distanceM), _turnPhrase(l10n, maneuver)));
      } else {
        await DashEngine.instance.playChime();
      }
    }
  }

  Future<void> _speak(String text) async {
    await _tts.setLanguage(deviceLocalizations().localeName == 'ru' ? 'ru-RU' : 'en-US');
    await _tts.speak(text);
  }

  /// Reset trip state when nav starts/stops so the next route announces cleanly.
  void resetTrip() {
    _lastManeuverKey = -1.0;
    _farDone = false;
    _nearDone = false;
    _arrived = false;
  }

  // TODO(voice): give real turn directions their own l10n phrasing instead
  // of collapsing them all to "continue" — see the class doc.
  String _turnPhrase(AppLocalizations l10n, Maneuver m) => switch (m.type) {
        ManeuverType.arrive => l10n.voiceTurnArrive,
        _ => l10n.voiceTurnContinue,
      };

  String _roundDist(AppLocalizations l10n, double m) {
    if (m >= 1000) return l10n.voiceDistanceKm((m / 1000.0).toStringAsFixed(1));
    if (m >= 500) return l10n.voiceDistance500m;
    if (m >= 200) return l10n.voiceDistance200m;
    return l10n.voiceDistanceM((m / 50).floor() * 50);
  }
}

final voiceModeProvider = StateProvider<VoiceMode>((ref) => VoiceManager.instance.mode);

import 'package:flutter_riverpod/legacy.dart';
import 'package:flutter_tts/flutter_tts.dart';
import 'package:opendash_dash_engine/opendash_dash_engine.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../l10n/app_localizations.dart';
import '../util/device_localizations.dart';
import 'maneuver_cue_scheduler.dart';
import 'route.dart';

enum VoiceMode { off, chime, full }

const _prefsKeyMode = 'voice_mode';

/// Turn-by-turn voice guidance with locally localized maneuver phrases.
///
/// [ManeuverCueScheduler] de-duplicates the 300/150/60 metre cues. FULL speaks
/// via `flutter_tts`; CHIME beeps through the native plugin's `playChime()`.
/// Every [ManeuverType] has a local phrase, independent of the SDK annotation
/// language, so English route annotations never change Russian voice guidance.
/// The dash glyph is a separate concern: `NavLoop` sends [Maneuver.dashCode].
class VoiceManager {
  VoiceManager._();
  static final VoiceManager instance = VoiceManager._();

  final _tts = FlutterTts();
  final _cueScheduler = ManeuverCueScheduler();
  VoiceMode _mode = VoiceMode.chime;

  VoiceMode get mode => _mode;

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
  Future<void> maybeAnnounce(
    Maneuver? maneuver,
    double distanceM,
    double remainingM,
  ) async {
    if (_mode == VoiceMode.off) return;
    final cue = _cueScheduler.next(
      maneuver: maneuver,
      distanceM: distanceM,
      remainingM: remainingM,
    );
    if (cue == null) return;

    final l10n = deviceLocalizations();
    if (_mode == VoiceMode.full) {
      final text = switch (cue) {
        ManeuverCue.advance300 => l10n.voiceIn(
          l10n.voiceDistanceM(300),
          _turnPhrase(l10n, maneuver!),
        ),
        ManeuverCue.advance150 => l10n.voiceIn(
          l10n.voiceDistanceM(150),
          _turnPhrase(l10n, maneuver!),
        ),
        ManeuverCue.now => l10n.voiceNow(_turnPhrase(l10n, maneuver!)),
        ManeuverCue.arrived => l10n.voiceArrived,
      };
      await _speak(text);
    } else {
      await DashEngine.instance.playChime();
    }
  }

  /// Sustained dash-connection-loss alert — see `DashConnectionAlertController`,
  /// the sole caller. Unlike [maybeAnnounce], this fires regardless of active
  /// navigation (with no destination set, too) since it's about the link to
  /// the dash itself, not a turn. Respects [VoiceMode.off] like everything
  /// else here; CHIME mode gets the chime (a full sentence would be
  /// pointless if the rider muted speech) rather than staying silent.
  Future<void> announceConnectionLost() async {
    if (_mode == VoiceMode.off) return;
    if (_mode == VoiceMode.full) {
      await _speak(deviceLocalizations().voiceDashDisconnected);
    } else {
      await DashEngine.instance.playChime();
    }
  }

  /// Companion to [announceConnectionLost] — called once the dash is back,
  /// only if the loss was actually announced (see the controller).
  Future<void> announceConnectionRestored() async {
    if (_mode == VoiceMode.off) return;
    if (_mode == VoiceMode.full) {
      await _speak(deviceLocalizations().voiceDashReconnected);
    } else {
      await DashEngine.instance.playChime();
    }
  }

  Future<void> _speak(String text) async {
    await _tts.setLanguage(
      deviceLocalizations().localeName == 'ru' ? 'ru-RU' : 'en-US',
    );
    await _tts.speak(text);
  }

  /// Reset trip state when nav starts/stops so the next route announces cleanly.
  void resetTrip() => _cueScheduler.reset();

  String _turnPhrase(AppLocalizations l10n, Maneuver m) => switch (m.type) {
    ManeuverType.straight => l10n.voiceTurnContinue,
    ManeuverType.slightLeft => l10n.voiceTurnSlightLeft,
    ManeuverType.slightRight => l10n.voiceTurnSlightRight,
    ManeuverType.left => l10n.voiceTurnLeft,
    ManeuverType.right => l10n.voiceTurnRight,
    ManeuverType.hardLeft => l10n.voiceTurnHardLeft,
    ManeuverType.hardRight => l10n.voiceTurnHardRight,
    ManeuverType.forkLeft => l10n.voiceTurnForkLeft,
    ManeuverType.forkRight => l10n.voiceTurnForkRight,
    ManeuverType.uturnLeft => l10n.voiceTurnUturnLeft,
    ManeuverType.uturnRight => l10n.voiceTurnUturnRight,
    ManeuverType.enterRoundabout => l10n.voiceTurnEnterRoundabout,
    ManeuverType.leaveRoundabout =>
      m.roundaboutExitNumber != null && m.roundaboutExitNumber! > 0
          ? l10n.voiceTurnLeaveRoundaboutNumber(m.roundaboutExitNumber!)
          : l10n.voiceTurnLeaveRoundabout,
    ManeuverType.boardFerry => l10n.voiceTurnBoardFerry,
    ManeuverType.leaveFerry => l10n.voiceTurnLeaveFerry,
    ManeuverType.exitLeft => l10n.voiceTurnExitLeft,
    ManeuverType.exitRight => l10n.voiceTurnExitRight,
    ManeuverType.finish || ManeuverType.arrive => l10n.voiceTurnArrive,
    ManeuverType.waypoint => l10n.voiceTurnWaypoint,
  };
}

final voiceModeProvider = StateProvider<VoiceMode>(
  (ref) => VoiceManager.instance.mode,
);

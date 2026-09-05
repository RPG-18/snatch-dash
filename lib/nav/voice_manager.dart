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
/// step-by-step instructions with hand-written per-direction l10n strings.
/// This port originally spoke `Maneuver.instruction` as-is — SDK-generated
/// `descriptionText` from `DrivingOptions.annotationLanguage` — but Yandex's
/// own guidance is not to rely on `descriptionText` for voice guidance (no
/// contract on wording/tense/language fidelity, it's meant for on-screen
/// display). [_turnPhrase] now builds the phrase itself, hand-written per
/// l10n like the original, for the three maneuvers that make up the bulk of
/// real turns: `left`, `right`, and both U-turn variants (spoken direction-
/// agnostic — "make a U-turn" either way). The synthetic `arrive` maneuver
/// (appended by `Router`, not from the SDK) keeps its own l10n phrase same
/// as before. Every other `ManeuverType` (slight/hard turns, forks, ramps,
/// roundabouts, ferries) still falls back to the SDK's `instruction`, or the
/// generic "continue" phrase if that's empty — narrowing that gap (i.e.
/// hand-writing the rest of the table) is follow-up work, not done here.
/// The dash glyph is a separate concern and still hardcoded to CONTINUE for
/// every turn (unverified glyph codes) — no turn-by-turn fidelity there either.
class VoiceManager {
  VoiceManager._();
  static final VoiceManager instance = VoiceManager._();

  static const _farM = 450.0;
  static const _nearM = 60.0;
  static const _arriveM = 30.0;

  final _tts = FlutterTts();
  VoiceMode _mode = VoiceMode.chime;

  /// The pending *future* of the preference read, not a "done" flag: a `bool`
  /// set before the `await` makes a second `load()` return immediately while
  /// [_mode] is still the default, so awaiting it would not mean what it says.
  /// Only `main.dart` calls this today, once and awaited, so this is a latent
  /// hazard rather than a live one — but it costs one field to remove.
  Future<void>? _loading;

  double _lastManeuverKey = -1.0;
  bool _farDone = false;
  bool _nearDone = false;
  bool _arrived = false;

  VoiceMode get mode => _mode;

  Future<void> load() async {
    final pending = _loading ??= _load();
    try {
      await pending;
    } catch (_) {
      // Never cache a failed read — a transient SharedPreferences failure must
      // not leave every later load() replaying it. Same shape as
      // `MaintenanceNotifier._ensureInitialized` and `AppDatabase.database`.
      if (identical(_loading, pending)) _loading = null;
      rethrow;
    }
  }

  Future<void> _load() async {
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

  /// See the class doc for why this doesn't just speak `m.instruction`
  /// anymore. The synthetic `arrive` maneuver has no SDK-generated
  /// `descriptionText` behind it regardless, so it always kept its own l10n
  /// phrase. `left`/`right` and the two U-turn variants are hand-written
  /// here now — U-turn direction matters (a left U-turn crosses oncoming
  /// traffic, a right one doesn't), so `uturnLeft`/`uturnRight` get distinct
  /// phrases rather than a shared "make a U-turn". Everything else still
  /// falls back to `m.instruction` (from `descriptionText`) or the generic
  /// "continue" phrase if that's empty.
  String _turnPhrase(AppLocalizations l10n, Maneuver m) => switch (m.type) {
        ManeuverType.arrive => l10n.voiceTurnArrive,
        ManeuverType.left => l10n.voiceTurnLeft,
        ManeuverType.right => l10n.voiceTurnRight,
        ManeuverType.uturnLeft => l10n.voiceTurnUturnLeft,
        ManeuverType.uturnRight => l10n.voiceTurnUturnRight,
        _ => m.instruction.isNotEmpty ? m.instruction : l10n.voiceTurnContinue,
      };

  String _roundDist(AppLocalizations l10n, double m) {
    if (m >= 1000) return l10n.voiceDistanceKm((m / 1000.0).toStringAsFixed(1));
    if (m >= 500) return l10n.voiceDistance500m;
    if (m >= 200) return l10n.voiceDistance200m;
    return l10n.voiceDistanceM((m / 50).floor() * 50);
  }
}

final voiceModeProvider = StateProvider<VoiceMode>((ref) => VoiceManager.instance.mode);
